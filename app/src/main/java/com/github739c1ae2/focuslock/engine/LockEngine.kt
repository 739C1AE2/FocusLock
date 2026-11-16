package com.github739c1ae2.focuslock.engine

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.collection.LruCache
import com.github739c1ae2.focuslock.adapter.AdapterFactoryRegistry
import com.github739c1ae2.focuslock.adapter.AdapterLockState
import com.github739c1ae2.focuslock.adapter.AppAdapter
import com.github739c1ae2.focuslock.adapter.StaticAdapter
import com.github739c1ae2.focuslock.database.ActiveLockSession
import com.github739c1ae2.focuslock.database.AppRuleMode
import com.github739c1ae2.focuslock.database.LockRepository
import com.github739c1ae2.focuslock.database.LockSessionUseCase
import com.github739c1ae2.focuslock.datastore.AppSettingsManager
import com.github739c1ae2.focuslock.datastore.UnlockUsageManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds


class LockEngine @AssistedInject constructor(
    private val useCase: LockSessionUseCase,
    private val repository: LockRepository,
    settingsManager: AppSettingsManager,
    private val unlockUsageManager: UnlockUsageManager,
    @Assisted private val service: AccessibilityService,
    @Assisted private val onError: (Throwable) -> Unit,
) {
    companion object {
        private const val TAG = "LockEngine"
        private val _runningStateFlow = MutableStateFlow<StateFlow<EngineState>?>(null)

        @OptIn(ExperimentalCoroutinesApi::class)
        val serviceState: StateFlow<ServiceState> = _runningStateFlow
            .flatMapLatest { internalFlow ->
                internalFlow?.map {
                    ServiceState.Running(it)
                } ?: flowOf(ServiceState.Stopped)
            }
            .stateIn(
                scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = ServiceState.Stopped
            )
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (!destroyed) onError(throwable)
    }

    /** 当前任务及其执行状态，业务处理和界面观察共用此状态。 */
    val engineState: StateFlow<EngineState>
        field = MutableStateFlow<EngineState>(EngineState.Idle)

    private val engineDispatcher = Dispatchers.Default.limitedParallelism(1)
    val engineScope = CoroutineScope(engineDispatcher + SupervisorJob() + exceptionHandler)

    private val eventChannel = Channel<EngineEvent>(Channel.UNLIMITED)

    private var countdownJob: Job? = null
    private var countdownGeneration = 0L

    private val sessionScheduler = SessionScheduler(useCase, service, engineScope, dispatch = {
        eventChannel.trySend(it)
    })

    @Volatile
    private var destroyed = false

    private val throttler = DynamicWindowThrottler(workerScope = engineScope) {
        eventChannel.trySend(EngineEvent.ResolveContent)
    }

    private var currentApp: String = ""

    // 使用 Volatile 保证在辅助功能线程读取 requiresContentUpdate 时的可见性
    @Volatile
    private var activeAdapter: AppAdapter = StaticAdapter.PASSED

    private val overlayManager: OverlayManager = OverlayManager(service, this)

    val overlayWindowType: StateFlow<Int> = settingsManager.useApplicationOverlayEnabled
        .map { useApplicationOverlay ->
            if (useApplicationOverlay && Settings.canDrawOverlays(service)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
            } else {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            }
        }
        .stateIn(
            scope = engineScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        )

    private val systemAppCache = LruCache<String, Boolean>(20)

    private val activityClassCache = LruCache<String, Boolean>(200)
    private val activityCache = LruCache<String, String>(20)

    init {
        _runningStateFlow.value = engineState

        engineScope.launch {
            for (firstEvent in eventChannel) {
                if (destroyed) break
                val batch = mutableListOf(firstEvent)
                while (true) {
                    val next = eventChannel.tryReceive().getOrNull() ?: break
                    batch.add(next)
                }
                val deduped = mutableListOf<EngineEvent>()
                val seenEvents = mutableSetOf<EngineEvent>()
                // 合并重复的重算通知，避免同一批事件反复读库；操作、窗口和计时事件不能丢弃。
                // 从尾部去重可保留最后一次通知的位置，避免改变它与其他事件的先后关系。
                for (event in batch.reversed()) {
                    when (event) {
                        EngineEvent.ResolveContent, EngineEvent.DatabaseChanged, EngineEvent.TimeChanged -> {
                            if (seenEvents.add(event)) {
                                deduped.add(event)
                            }
                        }

                        else -> {
                            deduped.add(event)
                        }
                    }
                }
                for (event in deduped.reversed()) {
                    processEvent(event)
                }
            }
        }
    }

    fun dispatchAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val eventPkg = event.packageName?.toString()
                val eventClassName = event.className?.toString()
                Log.d(TAG, "收到窗口状态变化事件: pkg=$eventPkg, class=$eventClassName")
                if (eventPkg != null && eventClassName != null) {
                    eventChannel.trySend(EngineEvent.WindowStateChanged(eventPkg, eventClassName))
                }
                throttler.request(50.milliseconds)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (activeAdapter.requiresContentUpdate) {
                    Log.d(TAG, "收到窗口内容变化事件")
                    throttler.request(200.milliseconds)
                }
            }

            else -> {}
        }
    }

    fun onActionReceived(action: EngineAction) {
        eventChannel.trySend(EngineEvent.ActionRequested(action))
    }

    fun destroy() {
        Log.d(TAG, "销毁 LockEngine")
        destroyed = true
        _runningStateFlow.value = null
        cancelCountdown()
        throttler.cancel()
        sessionScheduler.close()
        engineScope.cancel()
        eventChannel.close()
        overlayManager.destroy()
    }

    /** 处理一个引擎事件，更新任务、前台应用信息或计时状态。 */
    private suspend fun processEvent(event: EngineEvent) {
        when (event) {
            is EngineEvent.WindowStateChanged -> {
                if (isActivityClass(event.packageName, event.className) == true) {
                    activityCache.put(event.packageName, event.className)
                }
            }
            EngineEvent.ResolveContent -> evaluateCurrentState()
            EngineEvent.DatabaseChanged -> evaluateCurrentState(forceRefresh = true, reloadAdapter = true)
            EngineEvent.TimeChanged -> evaluateCurrentState(forceRefresh = true)
            EngineEvent.WakeupTimeReached -> evaluateCurrentState(forceRefresh = true)
            is EngineEvent.ActionRequested -> {
                // 数据变更事件可能仍在队列中；操作前取得最新的任务归属。
                evaluateCurrentState(forceRefresh = true)
                when (val action = event.action) {
                    is EngineAction.RequestPause -> handlePauseRequest(action.duration)
                    is EngineAction.RequestUnlock -> handleForceUnlock()
                }
            }
            is EngineEvent.WarningTick -> {
                val state = engineState.value
                if (state is EngineState.InSession && event.generation == countdownGeneration &&
                    state.sessionState is SessionState.Warning) {
                    engineState.value = state.copy(sessionState = SessionState.Warning(event.secondsLeft.seconds))
                }
            }
            is EngineEvent.TimerFinished -> {
                val state = engineState.value
                if (state is EngineState.InSession && event.generation == countdownGeneration) {
                    cancelCountdown()
                    engineState.value = state.copy(sessionState = SessionState.Allowed)
                    evaluateCurrentState()
                }
            }
        }
    }

    /** 读取前台应用节点，更新当前适配器的内容状态。 */
    private suspend fun resolveCurrentContent() {
        if (destroyed || engineState.value !is EngineState.InSession) return
        val node: AccessibilityNodeInfo = service.rootInActiveWindow ?: return
        val packageName = node.packageName?.toString()?.takeIf { it.isNotEmpty() } ?: return
        if (packageName != currentApp) {
            currentApp = packageName
            updateAdapter()
        }
        activeAdapter.onEvent(activityCache[currentApp], node)
    }

    /**
     * 根据锁定计划和前台内容更新引擎状态。
     *
     * @param forceRefresh 是否重新读取任务并更新唤醒等待。
     * @param reloadAdapter 任务未变时，是否在刷新计划时重新加载当前应用规则。
     */
    private suspend fun evaluateCurrentState(forceRefresh: Boolean = false, reloadAdapter: Boolean = false) {
        // 内容事件频繁，未到时间边界时沿用当前任务，避免反复读取数据库。
        if (forceRefresh || sessionScheduler.needsRefresh()) {
            val previous = (engineState.value as? EngineState.InSession)?.session
            val next = sessionScheduler.refresh(previous)
            applySession(next, reloadAdapter)
        }
        // 先完成任务和唤醒更新，前台节点暂时不可用时调度也能正常推进。
        resolveCurrentContent()

        val state = engineState.value as? EngineState.InSession ?: return
        if (state.sessionState is SessionState.Warning || state.sessionState == SessionState.Paused) {
            return
        }
        engineState.value = state.copy(sessionState =
            if (activeAdapter.currentLockState == AdapterLockState.BLOCK) SessionState.Locked else SessionState.Allowed)
    }

    /**
     * 应用活动任务，更新运行状态、适配器和入场警告。
     *
     * @param session 新的活动任务；为 null 时转为空闲。
     * @param reloadAdapter 任务未变时是否重新加载应用规则。
     */
    private suspend fun applySession(session: ActiveLockSession?, reloadAdapter: Boolean) {
        val previous = engineState.value as? EngineState.InSession
        if (session == null) {
            if (previous != null) {
                cancelCountdown()
                engineState.value = EngineState.Idle
                currentApp = ""
                updateAdapterInstance(StaticAdapter.PASSED)
            }
            return
        }
        if (previous?.session == session) {
            if (reloadAdapter) updateAdapter()
            return
        }

        Log.d(TAG, "活动时段变更: ${previous?.session} -> $session")
        // 暂停和警告属于当前执行状态，跨任务沿用会让旧任务的计时影响新任务。
        cancelCountdown()
        engineState.value = EngineState.InSession(session, SessionState.Allowed)
        updateAdapter()
        unlockUsageManager.onSessionChanged(session)
        // 只有从空闲进入时给予宽限，避免任务交接反复推迟锁定。
        if (previous == null) {
            val now = System.currentTimeMillis()
            // 迟进入仍预留短暂提示，但剩余窗口更短时以任务结束为上限。
            val warningMillis = minOf((session.startTimeMillis + 15_000 - now).coerceIn(3_000, 15_000),
                session.endTimeMillis - now)
            if (warningMillis > 0) startWarningCountdown(warningMillis.milliseconds)
        }
    }

    private suspend fun updateAdapter() {
        val state = engineState.value
        if (currentApp.isEmpty() || state !is EngineState.InSession) {
            updateAdapterInstance(StaticAdapter.PASSED)
            return
        }
        val profile = repository.getProfile(state.session.profileId)
        val rule = profile.rules[currentApp]
        if (rule?.appliedAdapterId != null) {
            val factory = AdapterFactoryRegistry.getFactoryById(rule.appliedAdapterId)
            updateAdapterInstance(factory.create(rule.adapterConfig))
            return
        }
        val isSystemApp = isSystemApp(currentApp)
        // 无法判断是否为系统应用时，为防止发生意外，默认不锁
        val locked = isSystemApp != null && if (rule == null) {
            if (isSystemApp) {
                profile.systemAppMode == AppRuleMode.WHITELIST
            } else {
                profile.userAppMode == AppRuleMode.WHITELIST
            }
        } else {
            if (isSystemApp) {
                profile.systemAppMode == AppRuleMode.BLACKLIST
            } else {
                profile.userAppMode == AppRuleMode.BLACKLIST
            }
        }
        updateAdapterInstance(
            if (locked) {
                StaticAdapter.BLOCKED
            } else {
                StaticAdapter.PASSED
            }
        )
    }

    private fun updateAdapterInstance(newAdapter: AppAdapter) {
        activeAdapter.onDetach()
        activeAdapter = newAdapter
        activeAdapter.onAttach(currentApp)
    }

    /** 取消当前警告或暂停倒计时，并使其待处理回调失效。 */
    private fun cancelCountdown() {
        // 取消协程不能撤回已入队的回调，版本号用于阻止它们修改新的计时状态。
        ++countdownGeneration
        countdownJob?.cancel()
        countdownJob = null
    }

    /** 启动警告倒计时，投递剩余秒数，并在结束时投递完成事件。 */
    private fun startWarningCountdown(duration: Duration) {
        cancelCountdown()
        val state = engineState.value as? EngineState.InSession ?: return
        engineState.value = state.copy(sessionState = SessionState.Warning(duration))
        val token = countdownGeneration
        // 警告按经过的时间计时，使用单调时钟以免系统改时延长或缩短倒计时。
        val end = SystemClock.elapsedRealtime() + duration.inWholeMilliseconds
        countdownJob = engineScope.launch {
            while (isActive) {
                val remaining = end - SystemClock.elapsedRealtime()
                if (remaining <= 0) break
                eventChannel.send(EngineEvent.WarningTick((remaining + 999) / 1000, token))
                delay(minOf(remaining, 1_000L).milliseconds)
            }
            if (isActive) eventChannel.send(EngineEvent.TimerFinished(token))
        }
    }

    private fun handlePauseRequest(duration: Duration) {
        val state = engineState.value as? EngineState.InSession ?: return
        if (duration <= Duration.ZERO || state.sessionState == SessionState.Paused) return
        cancelCountdown()
        engineState.value = state.copy(sessionState = SessionState.Paused)
        val token = countdownGeneration
        countdownJob = engineScope.launch {
            delay(duration)
            if (isActive) eventChannel.send(EngineEvent.TimerFinished(token))
        }
    }

    /** 取消当前时刻生效的全部锁定任务，并重新求值。 */
    private suspend fun handleForceUnlock() {
        if (engineState.value !is EngineState.InSession) return
        useCase.cancelActiveSessions()
        // 批量取消完成后再重算，避免逐个取消时切换到其他被遮住的任务。
        evaluateCurrentState(forceRefresh = true)
    }

    private fun isSystemApp(packageName: String): Boolean? {
        systemAppCache[packageName]?.let { return it }
        val isSystem = try {
            val appInfo = service.packageManager.getApplicationInfo(packageName, 0)
            (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (e: Exception) {
            Log.e(TAG, "ImportError while checking if $packageName is system app", e)
            null
        }
        if (isSystem != null) {
            systemAppCache.put(packageName, isSystem)
        }
        return isSystem
    }

    val viewPackagePrefixes = arrayOf(
        "android.view.",
        "android.widget.",
        "android.webkit.",
        "android.appwidget.",
        "android.inputmethodservice.",
        "androidx.recyclerview.",
        "androidx.compose.",
        "com.google.android.material."
    )

    private fun isActivityClass(packageName: String, className: String): Boolean? {
        val cacheKey = "$packageName/$className"
        activityClassCache[cacheKey]?.let { return it }
        val isActivity = try {
            service.packageManager.getActivityInfo(ComponentName(packageName, className), 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            Log.e(TAG, "ImportError while checking if $cacheKey is activity", e)
            if (viewPackagePrefixes.any { className.startsWith(it) }) {
                false
            } else if (className.endsWith("Activity")) {
                true
            } else {
                null
            }
        }
        if (isActivity != null) {
            activityClassCache.put(cacheKey, isActivity)
        }
        return isActivity
    }

    @AssistedFactory
    interface Factory {
        fun create(
            service: AccessibilityService,
            onError: (Throwable) -> Unit
        ): LockEngine
    }
}
