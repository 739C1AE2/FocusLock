package com.github739c1ae2.focuslock.engine

import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import com.github739c1ae2.focuslock.database.ScheduleEntity
import kotlin.time.Duration


sealed class EngineState {
    object Idle : EngineState()

    data class Warning(
        val remaining: Duration,
    ) : EngineState()
    data class Locked(
        val schedule: ScheduleEntity
    ) : EngineState()

    object Paused : EngineState()
    object Allowed : EngineState()

}