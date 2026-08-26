package com.github739c1ae2.focuslock.engine

import com.github739c1ae2.focuslock.database.ActiveLockSession
import kotlin.time.Duration

//
//sealed class EngineState {
//    object Idle : EngineState()
//
//    data class Warning(
//        val remaining: Duration,
//    ) : EngineState()
//    data class Locked(
//        val session: ActiveLockSession
//    ) : EngineState()
//
//    object Paused : EngineState()
//    object Allowed : EngineState()
//
//}

sealed interface SessionState {
    data class Warning(
        val remaining: Duration,
    ) : SessionState

    object Locked : SessionState
    object Paused : SessionState
    object Allowed : SessionState
}

sealed interface EngineState {
    object Idle : EngineState
    data class InSession(
        val session: ActiveLockSession,
        val sessionState: SessionState
    ) : EngineState
}

sealed interface ServiceState {
    object Stopped : ServiceState
    data class Running(
        val engineState: EngineState
    ) : ServiceState
}