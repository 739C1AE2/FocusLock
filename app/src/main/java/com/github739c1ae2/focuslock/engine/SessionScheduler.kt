package com.github739c1ae2.focuslock.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.github739c1ae2.focuslock.database.ActiveLockSession
import com.github739c1ae2.focuslock.database.LockSessionUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** 观察任务和系统时间变化，刷新锁定计划并管理其唤醒等待。 */
class SessionScheduler(
    private val useCase: LockSessionUseCase,
    private val context: Context,
    private val scope: CoroutineScope,
    private val dispatch: (EngineEvent) -> Unit,
) {
    // 没有后续唤醒也可能是有效结果，需要区分它与尚未刷新。
    private var hasRefreshed = false
    private var wakeupJob: Job? = null
    private var scheduledAtMillis: Long? = null
    private val observationJob: Job

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            dispatch(EngineEvent.TimeChanged)
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            // 休眠期间协程可能错过边界，亮屏后直接按实际时间重算。
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(context, timeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        observationJob = scope.launch {
            useCase.observeChanges().collect { dispatch(EngineEvent.DatabaseChanged) }
        }
    }

    /** 判断是否尚未取得计划，或已到达最近一次计划的唤醒时间。 */
    fun needsRefresh(): Boolean {
        return !hasRefreshed || scheduledAtMillis?.let { System.currentTimeMillis() >= it } == true
    }

    /**
     * 重新读取锁定计划，处理旧任务的自然完成，并更新唤醒等待。
     *
     * @param previousSession 刷新前正在执行的任务；空闲时为 null。
     * @return 新的活动任务；没有命中任务时为 null。
     */
    suspend fun refresh(previousSession: ActiveLockSession?): ActiveLockSession? {
        var plan = useCase.getSessionPlan()
        // 读库期间也可能跨过结束点，因此在读取后检查旧任务是否完成。
        if (previousSession != null && useCase.completeSession(previousSession)) {
            // 停用会改变候选任务，不能继续使用停用前的计划。
            plan = useCase.getSessionPlan()
        }
        schedule(plan.nextWakeupMillis, if (plan.activeSession == null) 5.minutes else 1.minutes)
        hasRefreshed = true
        return plan.activeSession
    }

    /**
     * 更新唤醒目标，重新建立等待任务。
     *
     * @param targetMillis 目标 Unix 毫秒时间戳；为 null 时取消唤醒等待。
     * @param maxSleep 每段等待的最大时长。
     */
    private fun schedule(targetMillis: Long?, maxSleep: Duration) {
        wakeupJob?.cancel()
        wakeupJob = null
        scheduledAtMillis = targetMillis
        if (targetMillis == null) return
        wakeupJob = scope.launch {
            delayUntil(targetMillis, maxSleep)
            if (isActive) dispatch(EngineEvent.WakeupTimeReached)
        }
    }

    /** 分段等待到指定 Unix 毫秒时间戳，每段最多等待 maxSleep。 */
    private suspend fun delayUntil(targetTimestamp: Long, maxSleep: Duration) {
        while (currentCoroutineContext().isActive) {
            // 每段重新按绝对时间校准，避免休眠或改时造成的偏差累积到整个等待时长。
            val remaining = targetTimestamp - System.currentTimeMillis()
            if (remaining <= 0) return
            delay(minOf(remaining.milliseconds, maxSleep))
        }
    }

    /** 取消唤醒和数据观察，注销系统广播接收器。 */
    fun close() {
        wakeupJob?.cancel()
        observationJob.cancel()
        context.unregisterReceiver(timeReceiver)
    }
}
