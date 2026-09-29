package com.github739c1ae2.focuslock.engine

import android.accessibilityservice.AccessibilityService
import android.os.Build
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
import com.github739c1ae2.focuslock.datastore.AppSettingsManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds


class LockEngine(
    private val service: AccessibilityService,
    private val repository: LockRepository,
    settingsManager: AppSettingsManager,
    private val onError: (Throwable) -> Unit
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
        onError(throwable)
    }

    val engineState: StateFlow<EngineState>
        field = MutableStateFlow<EngineState>(EngineState.Idle)

    private val engineDispatcher = Dispatchers.Default.limitedParallelism(1)
    val engineScope = CoroutineScope(engineDispatcher + SupervisorJob() + exceptionHandler)

    private val eventChannel = Channel<EngineEvent>(Channel.UNLIMITED)

    private var countdownJob: Job? = null
    private var timeTriggerJob: Job? = null

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

    private val activityCache = LruCache<String, String>(20)

    init {
        _runningStateFlow.value = engineState

        engineScope.launch {
            for (event in eventChannel) {
                if (destroyed) break
                processEvent(event)
            }
        }

        engineScope.launch {
            repository.invalidationTracker
                .createFlow("schedules", "quick_lock", emitInitialState = true)
                .collect {
                    Log.d(TAG, "数据库发生变化，发送重估事件")
                    eventChannel.send(EngineEvent.DatabaseChanged)
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
        eventChannel.close()
        overlayManager.destroy()
        countdownJob?.cancel()
        throttler.cancel()
        timeTriggerJob?.cancel()
        engineScope.cancel()
    }

    private suspend fun processEvent(event: EngineEvent) {
        when (event) {
            is EngineEvent.WindowStateChanged -> {
                activityCache.put(event.packageName, event.className)
            }
            is EngineEvent.ResolveContent -> {
                resolveCurrentContent()
            }
            is EngineEvent.DatabaseChanged -> {
                evaluateCurrentState()
            }
            is EngineEvent.ActionRequested -> {
                when (event.action) {
                    is EngineAction.RequestPause -> handlePauseRequest(event.action.duration)
                    is EngineAction.RequestUnlock -> handleForceUnlock()
                }
            }
            is EngineEvent.WakeupTimeReached -> {
                val currentSession = (engineState.value as? EngineState.InSession)?.session
                if (event.expectedSession == null || event.expectedSession == currentSession) {
                    evaluateCurrentState()
                }
            }
            is EngineEvent.WarningTick -> {
                val state = engineState.value
                if (state is EngineState.InSession && state.session == event.session && state.sessionState is SessionState.Warning) {
                    engineState.value = state.copy(sessionState = SessionState.Warning(event.secondsLeft.seconds))
                }
            }
            is EngineEvent.TimerFinished -> {
                val state = engineState.value
                if (state is EngineState.InSession && state.session == event.session) {
                    // 定时器结束，恢复到 Allowed 状态并重新评估
                    engineState.value = state.copy(sessionState = SessionState.Allowed)
                    evaluateCurrentState()
                }
            }
        }
    }

    private suspend fun resolveCurrentContent() {
        if (destroyed) {
            return
        }
//        var focusedPkg: String? = null
//        var focusedNode: AccessibilityNodeInfo? = null

//        val windows = service.windows
//        for (window in windows) {
//            if (window == null) {
//                continue
//            }
//            if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
//                if (window.isFocused || window.isActive) {
//                    val root = window.root ?: continue
//                    val resolvedPkg = root.packageName?.toString() ?: continue
//                    focusedPkg = resolvedPkg
//                    focusedNode = root
//                    break
//                }
//            }
//        }
        if (engineState.value is EngineState.InSession) {
            val focusedNode: AccessibilityNodeInfo? = service.rootInActiveWindow
            val focusedPkg = focusedNode?.packageName?.toString() ?: return
            if (focusedPkg.isEmpty()) {
                Log.w(TAG, "无法解析当前应用包名")
                return
            }
            if (focusedPkg != currentApp) {
                currentApp = focusedPkg
                Log.d(TAG, "当前应用已变更为: $currentApp")
                updateAdapter()
            }
            activeAdapter.onEvent(activityCache[currentApp], focusedNode)
        }
        evaluateCurrentState()
    }

    private suspend fun evaluateCurrentState() {
        val state = engineState.value

        if (state is EngineState.InSession && state.sessionState is SessionState.Paused) {
            // 暂停状态下不需要做任何处理，包括更新 session，完成后会被设为 Allowed，从而触发状态更新
            return
        }
        val session = repository.getActiveSession()

        if (session == null) {
            setIdle()
            return
        }

        val oldSession = (state as? EngineState.InSession)?.session
        if (oldSession != session) {
            changeSession(session, oldSession)
            // changeSession 只是更新了适配器对象，适配器还不知道当前界面的内容，
            // 重新解析当前内容，这会更新适配器解析状态
            resolveCurrentContent()
            // 当前状态还没准备完毕，直接 return
            // resolveCurrentContent 会在完成后重新调用 evaluateCurrentState
            return
        }

        // 前面所有的可能更改 sessionState 的分支都提前 return 了，所以 engineState 还没有改变
        when (state.sessionState) {

            is SessionState.Allowed, is SessionState.Locked -> {
                val isLocked = activeAdapter.currentLockState == AdapterLockState.BLOCK
                if (isLocked) {
                    engineState.value = state.copy(sessionState = SessionState.Locked)
                } else {
                    engineState.value = state.copy(sessionState = SessionState.Allowed)
                }
            }

            SessionState.Paused, is SessionState.Warning -> {
                // 警告状态下不需要做任何处理，等待倒计时结束后，会被设为 Allowed，从而触发状态更新
                return
            }
        }
    }

    private suspend fun changeSession(
        session: ActiveLockSession,
        oldSession: ActiveLockSession?
    ) {
        Log.d(TAG, "活动时段已变更为: $session, 之前的时段: $oldSession")

        countdownJob?.cancel()
        countdownJob = null

        oldSession?.let {
            repository.completeSession(it)
        }

        engineState.value = EngineState.InSession(session, SessionState.Allowed)

        // 重新设置唤醒时间
        scheduleWakeupForSession(session)
        // 将适配器更新为当前时段的
        updateAdapter()

        if (oldSession == null) {
            // 之前没有 Session，说明是从空闲状态进入了 Session，需要启动警告倒计时
            val duration = (session.startTimeMillis + 15000 - System.currentTimeMillis())
                .coerceIn(3000, 15000)
            startWarningCountdown(duration.milliseconds, session)
        }
    }

    private suspend fun updateAdapter() {
        val state = engineState.value
        if (currentApp.isEmpty() || state !is EngineState.InSession) {
            updateAdapterInstance(StaticAdapter.PASSED)
            return
        }
        val profile = requireNotNull(repository.getCompleteProfile(state.session.profileId))
        val rule = profile.rules[currentApp]
        if (rule?.appliedAdapterId != null) {
            val factory = AdapterFactoryRegistry.getFactoryById(rule.appliedAdapterId)
            updateAdapterInstance(factory.create(rule.adapterConfig))
            return
        }
        val isSystemApp = isSystemApp(currentApp)
        val locked = if (isSystemApp == null) {
            // 无法判断是否为系统应用，为防止发生意外，默认不锁
            false
        } else if (rule == null) {
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
        updateAdapterInstance(if (locked) {
            StaticAdapter.BLOCKED
        } else {
            StaticAdapter.PASSED
        })
    }

    private fun updateAdapterInstance(newAdapter: AppAdapter) {
        activeAdapter.onDetach()
        activeAdapter = newAdapter
        activeAdapter.onAttach(currentApp)
    }

    private fun startWarningCountdown(duration: Duration, session: ActiveLockSession) {
        countdownJob?.cancel()

        val state = engineState.value
        require(state is EngineState.InSession)

        engineState.update {
            require(it is EngineState.InSession)
            it.copy(sessionState = SessionState.Warning(duration))
        }
        countdownJob = engineScope.launch {
            var secondsLeft = duration.inWholeSeconds
            val endTimeMillis = System.currentTimeMillis() + secondsLeft * 1000
            while (isActive && secondsLeft >= 0) {
                eventChannel.send(EngineEvent.WarningTick(secondsLeft, session))
                val targetTimeMillis = endTimeMillis - secondsLeft * 1000
                val delayMillis = targetTimeMillis - System.currentTimeMillis()
                if (delayMillis > 0) {
                    delay(delayMillis.milliseconds)
                }
                secondsLeft--
            }

            if (isActive) {
                eventChannel.send(EngineEvent.TimerFinished(EngineEvent.TimerType.WARNING, session))
            }
        }
    }

    private fun handlePauseRequest(duration: Duration) {
        countdownJob?.cancel()

        val state = engineState.value
        if (state !is EngineState.InSession) {
            return
        }
        val session = state.session

        engineState.update {
            require(it is EngineState.InSession)
            it.copy(sessionState = SessionState.Paused)
        }
        countdownJob = engineScope.launch {
            delay(duration)
            if (isActive) {
                eventChannel.send(EngineEvent.TimerFinished(EngineEvent.TimerType.PAUSE, session))
            }
        }
    }

    private fun scheduleWakeupForSession(session: ActiveLockSession) {
        timeTriggerJob?.cancel()
        timeTriggerJob = null

        val now = System.currentTimeMillis()
        val endTime = session.endTimeMillis

        val delayMs = endTime - now
        if (delayMs > 0) {
            timeTriggerJob = engineScope.launch {
                delayUntil(endTime, 1.minutes)
                if (isActive) {
                    eventChannel.send(EngineEvent.WakeupTimeReached(session))
                }
            }
        }
    }

    /**
     * 当前闲置时，向数据库查询最近的下一个任务时间，到点自动唤醒
     */
    private suspend fun scheduleNextWakeup() {
        if (engineState.value !is EngineState.Idle) return
        timeTriggerJob?.cancel()
        timeTriggerJob = null

        val now = System.currentTimeMillis()
        val nextStartTime = repository.getNextScheduleStartTimeMillis(now)
        if (nextStartTime != null) {
            val delayMs = nextStartTime - now
            if (delayMs > 0) {
                timeTriggerJob = engineScope.launch {
                    delayUntil(nextStartTime, 5.minutes)
                    if (isActive) {
                        eventChannel.send(EngineEvent.WakeupTimeReached(null))
                    }
                }
            }
        }
    }

    private suspend fun delayUntil(
        targetTimestamp: Long,
        maxSleepMillis: Duration
    ) {
        while (currentCoroutineContext().isActive) {
            val remaining = targetTimestamp - System.currentTimeMillis()
            if (remaining <= 0) {
                break
            }
            delay(minOf(remaining.milliseconds, maxSleepMillis))
        }
    }

    private suspend fun handleForceUnlock() {
        countdownJob?.cancel()
        countdownJob = null

        (engineState.value as? EngineState.InSession)?.session?.let { session ->
            repository.cancelSession(session)
        }
        setIdle()
    }

    private suspend fun setIdle() {
        if (engineState.value is EngineState.Idle) {
            return
        }
        (engineState.value as? EngineState.InSession)?.session?.let {
            repository.completeSession(it)
        }
        countdownJob?.cancel()
        countdownJob = null
        timeTriggerJob?.cancel()
        timeTriggerJob = null
        engineState.value = EngineState.Idle
        updateAdapterInstance(StaticAdapter.PASSED)
        scheduleNextWakeup()
    }

    private fun isSystemApp(packageName: String): Boolean? {
        systemAppCache[packageName]?.let { return it }
        val isSystem = try {
            val appInfo = service.packageManager.getApplicationInfo(packageName, 0)
            (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (e: Exception) {
            Log.e(TAG, "Error while checking if $packageName is system app", e)
            null
        }
        if (isSystem != null) {
            systemAppCache.put(packageName, isSystem)
        }
        return isSystem
    }
}