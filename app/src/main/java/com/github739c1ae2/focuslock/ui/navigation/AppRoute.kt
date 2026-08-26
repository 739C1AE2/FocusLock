package com.github739c1ae2.focuslock.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavKey
import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.ui.screen.adapter.AdapterConfigInfo
import kotlinx.serialization.Serializable

sealed interface AppRoute : NavKey {

    sealed interface TopLevel : AppRoute

    @Serializable
    data object Home : TopLevel

    @Serializable
    data object Schedules : TopLevel

    @Serializable
    data object Profiles : TopLevel

    @Serializable
    data class ScheduleEditor(val scheduleId: Long) : AppRoute

    @Serializable
    data class ProfileEditor(val profileId: Long) : AppRoute

    @Serializable
    data class AdapterConfigEditor(val packageName: String, val original: AdapterConfigInfo?) : AppRoute
}

data class NavBarItem(
    val navKey: AppRoute.TopLevel,
    val icon: ImageVector,
    @StringRes val label: Int
)

val NAV_ITEMS = listOf(
    NavBarItem(AppRoute.Home, icon = Icons.Default.Home, label = R.string.nav_home),
    NavBarItem(AppRoute.Schedules, icon = Icons.Default.Schedule, label = R.string.nav_schedules),
    NavBarItem(AppRoute.Profiles, icon = Icons.AutoMirrored.Filled.Rule, label = R.string.nav_profiles)
)