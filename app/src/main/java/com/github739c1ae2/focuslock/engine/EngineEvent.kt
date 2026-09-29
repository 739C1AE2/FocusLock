package com.github739c1ae2.focuslock.engine

import com.github739c1ae2.focuslock.database.ActiveLockSession

sealed interface EngineEvent {
    data class WindowStateChanged(val packageName: String, val className: String) : EngineEvent
    object ResolveContent : EngineEvent
    object DatabaseChanged : EngineEvent
    data class ActionRequested(val action: EngineAction) : EngineEvent
    data class WakeupTimeReached(val expectedSession: ActiveLockSession?) : EngineEvent
    data class WarningTick(val secondsLeft: Long, val session: ActiveLockSession?) : EngineEvent
    data class TimerFinished(val type: TimerType, val session: ActiveLockSession) : EngineEvent

    enum class TimerType { WARNING, PAUSE }
}