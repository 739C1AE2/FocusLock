package com.github739c1ae2.focuslock.engine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.time.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * 动态窗口节流器 (Dynamic Window Throttler)
 * 具备 Leading-Edge (首发立即执行) 与 Trailing-Edge Fallback (尾随冷却兜底) 能力。
 *
 * @param workerScope 真正执行耗时任务的作用域（必须是单线程或有限并发，以防任务重叠）
 * @param stateDispatcher 维护内部状态机的调度器（默认用主线程，确保状态变更绝对安全无锁）
 * @param action 需要被节流执行的阻塞任务
 */
class DynamicWindowThrottler(
    private val workerScope: CoroutineScope,
    private val stateDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val action: () -> Unit
) {
    private val stateScope = CoroutineScope(stateDispatcher + SupervisorJob())

    private var isRunning = false
    private var lastCompletionTime = 0L
    private var waitTimerJob: Job? = null
    private var pendingFallbackDelayMs: Long? = null

    fun request(delay: Duration) {
        stateScope.launch {
            processRequest(delay.inWholeMilliseconds)
        }
    }

    private fun processRequest(delayMs: Long) {
        if (isRunning) {
            // 仅记录最新传入的延迟时间作为兜底
            pendingFallbackDelayMs = delayMs
            return
        }

        val elapsed = System.currentTimeMillis() - lastCompletionTime
        if (elapsed >= delayMs) {
            waitTimerJob?.cancel()
            executeTask()
        } else {
            // 还在冷却期，重置定时器，等待剩余时间
            val remaining = delayMs - elapsed
            waitTimerJob?.cancel()
            waitTimerJob = stateScope.launch {
                delay(remaining.milliseconds)
                executeTask()
            }
        }
    }

    // 执行核心任务并收尾
    private fun executeTask() {
        isRunning = true
        pendingFallbackDelayMs = null

        workerScope.launch {
            try {
                action()
            } finally {
                stateScope.launch {
                    lastCompletionTime = System.currentTimeMillis()
                    val nextDelay = pendingFallbackDelayMs

                    pendingFallbackDelayMs = null
                    isRunning = false

                    // 若执行期间有新请求，当作新事件处理，确保它经历完整的冷却期
                    if (nextDelay != null) {
                        processRequest(nextDelay)
                    }
                }
            }
        }
    }

    /**
     * 供 Engine 销毁时清理资源
     */
    fun cancel() {
        stateScope.cancel()
    }
}