package com.github739c1ae2.focuslock.engine

sealed interface EngineEvent {
    data class WindowStateChanged(val packageName: String, val className: String) : EngineEvent
    object ResolveContent : EngineEvent
    object DatabaseChanged : EngineEvent
    object TimeChanged : EngineEvent
    data class ActionRequested(val action: EngineAction) : EngineEvent
    /** 唤醒等待到期、需要重新计算当前计划的通知。 */
    object WakeupTimeReached : EngineEvent
    /** 警告倒计时剩余秒数的通知。 */
    data class WarningTick(val secondsLeft: Long, val generation: Long) : EngineEvent
    /** 警告或暂停倒计时结束的通知。 */
    data class TimerFinished(val generation: Long) : EngineEvent
}
