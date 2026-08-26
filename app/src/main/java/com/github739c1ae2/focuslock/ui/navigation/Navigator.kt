package com.github739c1ae2.focuslock.ui.navigation

import android.app.Activity
import androidx.navigation3.runtime.NavKey

class Navigator(
    val state: NavigationState,
    val activity: Activity
) {

    fun navigate(route: NavKey) {
        if (state.backStack.last() == route) {
            return
        }
        if (route is AppRoute.TopLevel) {
            val hasDirtyPage = state.backStack
                .subList(1, state.backStack.size)
                .any { it in state.dirtyKeys }
            if (hasDirtyPage) {
                state.pendingNavigation = PendingNavigation.Navigate(route)
                state.showWarning = true
                return
            }
        }
        forceNavigate(route)
    }

    fun forceNavigate(route: NavKey) {
        if (state.backStack.last() == route) {
            return
        }
        if (route is AppRoute.TopLevel) {
            state.backStack.subList(1, state.backStack.size).clear()
            state.topLevelRoute = route
            if (route == state.startRoute) {
                // 栈里一定有 startRoute，避免重复添加
                require(state.backStack.last() == state.startRoute)
                return
            }
        }
        state.backStack.add(route)
    }

    fun popToAndPush(anchor: NavKey, target: NavKey) {
        if (state.backStack.last() == target) {
            return
        }
        val anchorIndex = state.backStack.indexOf(anchor)
        if (anchorIndex != -1) {
            val routesToBePopped = state.backStack.subList(anchorIndex + 1, state.backStack.size)
            val hasDirtyPage = routesToBePopped.any { it in state.dirtyKeys }

            if (hasDirtyPage) {
                state.pendingNavigation = PendingNavigation.PopToAndPush(anchor, target)
                state.showWarning = true
                return
            }
        }
        forcePopToAndPush(anchor, target)
    }

    fun forcePopToAndPush(anchor: NavKey, target: NavKey) {
        if (state.backStack.last() == target) {
            return
        }
        while (state.backStack.size > 1 && state.backStack.lastOrNull() != anchor) {
            val poppedKey = state.backStack.lastOrNull()
            if (poppedKey != null) {
                state.dirtyKeys -= poppedKey
            }
            state.backStack.removeLastOrNull()
        }
        forceNavigate(target)
    }

    fun setRouteDirty(key: NavKey, isDirty: Boolean) {
        state.dirtyKeys = if (isDirty) {
            state.dirtyKeys + key
        } else {
            state.dirtyKeys - key
        }
    }

    fun safeGoBack(target: NavKey?) {
        val poppedKey = state.backStack.last()
        if (target != null && poppedKey != target) {
            return
        }
        if (state.isTopRouteDirty) {
            state.pendingNavigation = PendingNavigation.GoBack(poppedKey)
            state.showWarning = true
            return
        }
        forceGoBack(poppedKey)
    }

    fun forceGoBack(target: NavKey?) {
        val poppedKey = state.backStack.last()
        if (target != null && poppedKey != target) {
            return
        }
        state.dirtyKeys -= poppedKey
        state.showWarning = false
        state.backStack.removeLastOrNull()
        if (state.backStack.isEmpty()) {
            activity.finish()
            return
        }
        if (state.backStack.lastOrNull() == state.startRoute) {
            state.topLevelRoute = state.startRoute
        }
    }

    fun confirmDiscard() {
        val action = state.pendingNavigation
        state.showWarning = false
        state.pendingNavigation = null

        when (action) {
            is PendingNavigation.GoBack -> {
                forceGoBack(action.target)
            }

            is PendingNavigation.Navigate -> {
                forceNavigate(action.target)
            }

            is PendingNavigation.PopToAndPush -> {
                forcePopToAndPush(action.anchor, action.target)
            }

            null -> {
                forceGoBack(null)
            }
        }
    }

    fun cancelDiscard() {
        state.showWarning = false
        state.pendingNavigation = null
    }
}