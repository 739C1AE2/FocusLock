package com.github739c1ae2.focuslock.engine

import kotlin.time.Duration

sealed interface EngineAction {
    data class RequestPause(val duration: Duration) : EngineAction
    object RequestUnlock : EngineAction
}