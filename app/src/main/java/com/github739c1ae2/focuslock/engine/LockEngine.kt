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
import com.github739c1ae2.focuslock.database.FilterMode
import com.github739c1ae2.focuslock.database.LockRepository
import com.github739c1ae2.focuslock.database.ScheduleEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.properties.Delegates
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds


class LockEngine(
    private val service: AccessibilityService,
    private val repository: LockRepository
) {
    companion object {
        private const val TAG = "LockEngine"
    }

    private val _engineState = MutableStateFlow<EngineState>(EngineState.Idle)
    val engineState: StateFlow<EngineState> = _engineState

    private val engineDispatcher = Dispatchers.Default.limitedParallelism(1)
    val engineScope = CoroutineScope(engineDispatcher + SupervisorJob())

    private var countdownJob: Job? = null
    private var timeTriggerJob: Job? = null

    private val throttler = DynamicWindowThrottler(workerScope = engineScope) {
        resolveCurrentContent()
    }

    private var currentApp: String = ""

    private var activeSchedule: ScheduleEntity? = null
    private var activeAdapter: AppAdapter by Delegates.observable(StaticAdapter.PASSED) { _, oldValue, newValue ->
        oldValue.onDetach()
        newValue.onAttach(currentApp)
    }

    private val overlayManager: OverlayManager = OverlayManager(service, this)


    private val systemAppCache = LruCache<String, Boolean>(20)

    private val activityCache = LruCache<String, String>(20)

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
        if (_engineState.value != EngineState.Idle) {
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
        overlayManager.destroy()
        countdownJob?.cancel()
        throttler.cancel()
        timeTriggerJob?.cancel()
        engineScope.cancel()
    }


    private fun evaluateCurrentState() {
        if (_engineState.value is EngineState.Paused) return

        val schedule = runBlocking {
            repository.getActiveSchedules().firstOrNull()
        }

        if (schedule == null) {
            setIdle()
            return
        }

        if (activeSchedule != schedule) {
            Log.d(TAG, "活动时段已变更为: $schedule")
            activeSchedule = schedule
            // 锁机时段发生变化：通常是锁机开始或重叠时段的前一个时段结束
            scheduleWakeupForActiveSchedule()
            // 先将适配器更新为当前时段的
            updateAdapter()
            // 然后重新解析当前内容，这会更新适配器解析状态
            resolveCurrentContent()
            // 当前状态还没准备完毕，直接 return
            // resolveCurrentContent 会在完成后重新调用 evaluateCurrentState
            return
        }

        when (_engineState.value) {
            is EngineState.Idle -> {
                startWarningCountdown(15.seconds)
            }

            EngineState.Allowed, is EngineState.Locked -> {
                val isLocked = activeAdapter.currentLockState == AdapterLockState.BLOCK
                if (isLocked) {
                    _engineState.value = EngineState.Locked(schedule)
                } else {
                    _engineState.value = EngineState.Allowed
                }
            }

            EngineState.Paused, is EngineState.Warning -> {}
        }
    }

    private fun scheduleWakeupForActiveSchedule() {
        timeTriggerJob?.cancel()
        val now = System.currentTimeMillis()
        val endTime = activeSchedule?.getEndTimeMillisForToday(now) ?: return

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
        if (currentApp.isEmpty() || activeSchedule == null) {
            activeAdapter = StaticAdapter.PASSED
            return
        }
        val profile = runBlocking {
            requireNotNull(repository.getCompleteProfile(requireNotNull(activeSchedule).profileId))
        }
        val rule = profile.rules[currentApp]
        if (rule?.appliedAdapterId != null) {
            val factory = AdapterFactoryRegistry.getFactoryById(rule.appliedAdapterId)
            activeAdapter = factory.create(rule.adapterConfig)
            return
        }
        val locked = if (rule == null) {
            if (isSystemApp(currentApp)) {
                profile.systemAppMode == FilterMode.WHITELIST
            } else {
                profile.userAppMode == FilterMode.WHITELIST
            }
        } else {
            if (isSystemApp(currentApp)) {
                profile.systemAppMode == FilterMode.BLACKLIST
            } else {
                profile.userAppMode == FilterMode.BLACKLIST
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
        _engineState.value = EngineState.Warning(duration)
        countdownJob = engineScope.launch {
            var secondsLeft = duration.inWholeSeconds
            val endTimeMillis = System.currentTimeMillis() + secondsLeft * 1000
            while (isActive && secondsLeft > 0) {
                secondsLeft--
                _engineState.value = EngineState.Warning(secondsLeft.seconds)
                val targetTimeMillis = endTimeMillis - secondsLeft * 1000
                val delayMillis = targetTimeMillis - System.currentTimeMillis()
                if (delayMillis > 0) {
                    delay(delayMillis.milliseconds)
                }
            }
            _engineState.value = EngineState.Allowed
            evaluateCurrentState()
        }
    }

    private fun handlePauseRequest(duration: Duration) {
        countdownJob?.cancel()
        _engineState.value = EngineState.Paused
        countdownJob = engineScope.launch {
            delay(duration)
            _engineState.value = EngineState.Allowed
            evaluateCurrentState()
        }
    }

    private fun handleForceUnlock() {
        countdownJob?.cancel()
        activeSchedule?.let { schedule ->
            val disabledSchedule = schedule.copy(isActive = false)
            runBlocking {
                repository.saveSchedule(disabledSchedule)
            }
        }
        setIdle()
    }

    private fun setIdle() {
        countdownJob?.cancel()
        timeTriggerJob?.cancel()
        _engineState.value = EngineState.Idle
        activeSchedule = null
        activeAdapter = StaticAdapter.PASSED
        scheduleNextWakeup()
    }

    private fun isSystemApp(packageName: String): Boolean {
        systemAppCache[packageName]?.let { return it }
        val isSystem = try {
            val appInfo = service.packageManager.getApplicationInfo(packageName, 0)
            (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (e: Exception) {
            Log.e(TAG, "Error while checking if $packageName is system app", e)
            false
        }
        systemAppCache.put(packageName, isSystem)
        return isSystem
    }
}