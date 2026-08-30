package com.github739c1ae2.focuslock.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.serialization.NavKeySerializer
import androidx.savedstate.compose.serialization.serializers.MutableStateSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer


sealed interface PendingNavigation {
    data class GoBack(val target: NavKey?) : PendingNavigation
    data class GoBackMultiple(val count: Int) : PendingNavigation
    data class Navigate(val target: NavKey) : PendingNavigation
    data class PopToAndPush(val anchor: NavKey, val target: NavKey) : PendingNavigation
}

class NavigationState(
    val startRoute: AppRoute.TopLevel,
    topLevelRoute: MutableState<AppRoute.TopLevel>,
    dirtyKeys: MutableState<Set<NavKey>>,
    showWarning: MutableState<Boolean>,
    pendingNavigation: MutableState<PendingNavigation?>,
    val backStack: NavBackStack<NavKey>
) {
    var topLevelRoute by topLevelRoute
    var dirtyKeys by dirtyKeys
    var showWarning by showWarning
    var pendingNavigation by pendingNavigation

    fun isTopRoutesDirty(count: Int = 1): Boolean {
        val topRoutes = backStack.takeLast(count)
        return dirtyKeys.intersect(topRoutes.toSet()).isNotEmpty()
    }
}

@Composable
fun rememberNavigationState(
    startRoute: AppRoute.TopLevel
): NavigationState {
    val topLevelRoute = rememberSerializable(
        startRoute,
        serializer = MutableStateSerializer(NavKeySerializer())
    ) {
        mutableStateOf(startRoute)
    }
    val backStack = rememberNavBackStack(startRoute)

    val dirtyKeys = rememberSerializable(
        emptySet<NavKey>(),
        serializer = MutableStateSerializer(SetSerializer(NavKeySerializer()))
    ) {
        mutableStateOf(emptySet())
    }

    val showWarning = rememberSerializable(
        false,
        serializer = MutableStateSerializer(Boolean.serializer())
    ) {
        mutableStateOf(false)
    }

    val pendingNavigation = remember {
        mutableStateOf<PendingNavigation?>(null)
    }

    return remember(startRoute) {
        NavigationState(
            startRoute,
            topLevelRoute,
            dirtyKeys,
            showWarning,
            pendingNavigation,
            backStack
        )
    }
}
