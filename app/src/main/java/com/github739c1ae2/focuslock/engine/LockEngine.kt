package com.github739c1ae2.focuslock.engine

import android.accessibilityservice.AccessibilityService
import android.util.Log
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import kotlinx.coroutines.runBlocking
import kotlin.properties.Delegates
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds


@Suppress("RunBlocking")
class LockEngine(
    private val service: AccessibilityService,
    private val repository: LockRepository
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

    val engineState: StateFlow<EngineState>
        field = MutableStateFlow<EngineState>(EngineState.Idle)

    private val engineDispatcher = Dispatchers.Default.limitedParallelism(1)
    val engineScope = CoroutineScope(engineDispatcher + SupervisorJob())

    private var countdownJob: Job? = null
    private var timeTriggerJob: Job? = null

    private val throttler = DynamicWindowThrottler(workerScope = engineScope) {
        resolveCurrentContent()
    }

    private var currentApp: String = ""

    private var activeAdapter: AppAdapter by Delegates.observable(StaticAdapter.PASSED) { _, oldValue, newValue ->
        oldValue.onDetach()
        newValue.onAttach(currentApp)
    }

    private val overlayManager: OverlayManager = OverlayManager(service, this)


    private val systemAppCache = LruCache<String, Boolean>(20)

    private val activityCache = LruCache<String, String>(20)

    init {
        _runningStateFlow.value = engineState
        engineScope.launch {
            repository.invalidationTracker
                .createFlow("schedules", "quick_lock", emitInitialState = false)
                .collect {
                    Log.d(TAG, "数据库发生变化，重新评估当前状态")
                    evaluateCurrentState()
                    // 下次唤醒时间可能没有被更新，手动更新一下
                    scheduleNextWakeup()
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
                    activityCache.put(eventPkg, eventClassName)
                }
                throttler.request(50.milliseconds)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                Log.d(TAG, "收到窗口内容变化事件")
                if (activeAdapter.requiresContentUpdate) {
                    throttler.request(200.milliseconds)
                }
            }

            else -> {}
        }
    }

    private fun resolveCurrentContent() {
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

    fun onActionReceived(action: EngineAction) {
        engineScope.launch {
            when (action) {
                is EngineAction.RequestPause -> handlePauseRequest(action.duration)
                is EngineAction.RequestUnlock -> handleForceUnlock()
            }
        }
    }

    fun destroy() {
        Log.d(TAG, "销毁 LockEngine")
        _runningStateFlow.value = null
        overlayManager.destroy()
        countdownJob?.cancel()
        throttler.cancel()
        timeTriggerJob?.cancel()
        engineScope.cancel()
    }


    private fun evaluateCurrentState() {
        val state = engineState.value

        if (state is EngineState.InSession && state.sessionState is SessionState.Paused) {
            // 暂停状态下不需要做任何处理，包括更新 session，完成后会被设为 Allowed，从而触发状态更新
            return
        }
        val session = runBlocking {
            repository.getActiveSession()
        }

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

    private fun changeSession(session: ActiveLockSession, oldSession: ActiveLockSession?) {
        Log.d(TAG, "活动时段已变更为: $session, 之前的时段: $oldSession")
        oldSession?.let {
            runBlocking {
                repository.completeSession(it)
            }
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
            startWarningCountdown(duration.milliseconds)
        }
    }

    private fun scheduleWakeupForSession(session: ActiveLockSession) {
        timeTriggerJob?.cancel()
        val now = System.currentTimeMillis()
        val endTime = session.endTimeMillis

        val delayMs = endTime - now
        if (delayMs > 0) {
            timeTriggerJob = engineScope.launch {
                delayUntil(endTime, 1.minutes)
                evaluateCurrentState()
            }
        }
    }

    /**
     * 当前闲置时，向数据库查询最近的下一个任务时间，到点自动唤醒
     */
    private fun scheduleNextWakeup() {
        if (engineState.value !is EngineState.Idle) return
        timeTriggerJob?.cancel()
        val now = System.currentTimeMillis()
        val nextStartTime = runBlocking {
            repository.getNextScheduleStartTimeMillis(now)
        }
        if (nextStartTime != null) {
            val delayMs = nextStartTime - now
            if (delayMs > 0) {
                timeTriggerJob = engineScope.launch {
                    delayUntil(nextStartTime, 5.minutes)
                    evaluateCurrentState()
                }
            }
        }
    }

    private suspend fun delayUntil(
        targetTimestamp: Long,
        maxSleepMillis: Duration
    ) {
        while (true) {
            val remaining = targetTimestamp - System.currentTimeMillis()
            if (remaining <= 0) {
                break
            }
            delay(minOf(remaining.milliseconds, maxSleepMillis))
        }
    }

    private fun updateAdapter() {
        val state = engineState.value
        if (currentApp.isEmpty() || state !is EngineState.InSession) {
            activeAdapter = StaticAdapter.PASSED
            return
        }
        val profile = runBlocking {
            requireNotNull(repository.getCompleteProfile(state.session.profileId))
        }
        val rule = profile.rules[currentApp]
        if (rule?.appliedAdapterId != null) {
            val factory = AdapterFactoryRegistry.getFactoryById(rule.appliedAdapterId)
            activeAdapter = factory.create(rule.adapterConfig)
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
        activeAdapter = if (locked) {
            StaticAdapter.BLOCKED
        } else {
            StaticAdapter.PASSED
        }
    }

    private fun startWarningCountdown(duration: Duration) {
        countdownJob?.cancel()
        engineState.update {
            require(it is EngineState.InSession)
            it.copy(sessionState = SessionState.Warning(duration))
        }
        countdownJob = engineScope.launch {
            var secondsLeft = duration.inWholeSeconds
            val endTimeMillis = System.currentTimeMillis() + secondsLeft * 1000
            while (isActive && secondsLeft > 0) {
                secondsLeft--
                engineState.update {
                    if (it !is EngineState.InSession || it.sessionState !is SessionState.Warning) {
                        // 用户可能突然取消了当前时段？当然这是不该被允许操作的
                        Log.w(TAG, "倒计时期间，状态意外变更为: $it")
                        return@launch
                    }
                    it.copy(sessionState = SessionState.Warning(secondsLeft.seconds))
                }
                val targetTimeMillis = endTimeMillis - secondsLeft * 1000
                val delayMillis = targetTimeMillis - System.currentTimeMillis()
                if (delayMillis > 0) {
                    delay(delayMillis.milliseconds)
                }
            }
            engineState.update {
                if (it !is EngineState.InSession || it.sessionState !is SessionState.Warning) {
                    Log.w(TAG, "倒计时期间，状态意外变更为: $it")
                    return@launch
                }
                it.copy(sessionState = SessionState.Allowed)
            }
            evaluateCurrentState()
        }
    }

    private fun handlePauseRequest(duration: Duration) {
        countdownJob?.cancel()
        engineState.update {
            require(it is EngineState.InSession)
            it.copy(sessionState = SessionState.Paused)
        }
        countdownJob = engineScope.launch {
            delay(duration)
            engineState.update {
                when (it) {
                    is EngineState.InSession -> it.copy(sessionState = SessionState.Allowed)
                    else -> it
                }
            }
            evaluateCurrentState()
        }
    }

    private fun handleForceUnlock() {
        countdownJob?.cancel()
        (engineState.value as? EngineState.InSession)?.session?.let { session ->
            runBlocking {
                repository.cancelSession(session)
            }
        }
        setIdle()
    }

    private fun setIdle() {
        if (engineState.value is EngineState.Idle) {
            return
        }
        (engineState.value as? EngineState.InSession)?.session?.let {
            runBlocking {
                repository.completeSession(it)
            }
        }
        countdownJob?.cancel()
        timeTriggerJob?.cancel()
        engineState.value = EngineState.Idle
        activeAdapter = StaticAdapter.PASSED
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