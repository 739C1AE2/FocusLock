package com.github739c1ae2.focuslock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.LocalListDetailSceneScope
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.runtime.result.LocalResultEventBus
import androidx.navigation3.runtime.result.ResultEffect
import androidx.navigation3.runtime.result.rememberResultEventBusNavEntryDecorator
import androidx.navigation3.runtime.serialization.NavKeySerializer
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.compose.serialization.serializers.MutableStateSerializer
import androidx.window.core.layout.WindowSizeClass
import com.github739c1ae2.focuslock.ui.navigation.AppRoute
import com.github739c1ae2.focuslock.ui.navigation.NAV_ITEMS
import com.github739c1ae2.focuslock.ui.screen.adapter.AdapterConfigScreen
import com.github739c1ae2.focuslock.ui.screen.home.HomeScreen
import com.github739c1ae2.focuslock.ui.screen.profile.AdapterConfigResult
import com.github739c1ae2.focuslock.ui.screen.profile.ProfileEditScreen
import com.github739c1ae2.focuslock.ui.screen.profile.ProfileEditViewModel
import com.github739c1ae2.focuslock.ui.screen.profile.ProfileListScreen
import com.github739c1ae2.focuslock.ui.screen.schedule.ScheduleEditScreen
import com.github739c1ae2.focuslock.ui.screen.schedule.ScheduleListScreen
import com.github739c1ae2.focuslock.ui.theme.FocusLockTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FocusLockTheme {
                MainApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun MainApp() {
    val navigationState = rememberNavigationState(
        startRoute = AppRoute.Home
    )
    val navigator = remember(navigationState) { Navigator(navigationState) }
    val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>()
    val scaffoldState = rememberNavigationSuiteScaffoldState()

    val windowAdaptiveInfo = currentWindowAdaptiveInfoV2()
    val isLargeScreen = windowAdaptiveInfo.windowSizeClass.isAtLeastBreakpoint(
        WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
        WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND
    )
    val isTopLevelDestination by remember(navigationState) {
        derivedStateOf {
            navigationState.backStack.lastOrNull() is AppRoute.TopLevel
        }
    }
    LaunchedEffect(isTopLevelDestination, isLargeScreen) {
        if (isLargeScreen || isTopLevelDestination) {
            scaffoldState.show()
        } else {
            scaffoldState.hide()
        }
    }

    val navBarItems = NAV_ITEMS

    NavigationSuiteScaffold(
        navigationItems = {
            navBarItems.forEach { item ->
                NavigationSuiteItem(
                    selected = navigationState.topLevelRoute == item.navKey,
                    onClick = {
                        navigator.navigate(item.navKey)
                    },
                    icon = { Icon(item.icon, contentDescription = null) },
                    label = { Text(stringResource(item.label)) }
                )
            }
        },
        state = scaffoldState
    ) {
        NavDisplay(
            backStack = navigationState.backStack,
            onBack = {
                navigator.forceGoBack()
            },
            sceneStrategies = listOf(listDetailStrategy),
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
                rememberResultEventBusNavEntryDecorator()
            ),
            entryProvider = entryProvider {
                entry<AppRoute.Home>(
                    metadata = ListDetailSceneStrategy.listPane()
                ) {
                    HomeScreen()
                }
                entry<AppRoute.Schedules>(
                    metadata = ListDetailSceneStrategy.listPane(sceneKey = "Schedule")
                ) { key ->
                    val isListDetailScene = LocalListDetailSceneScope.current != null
                    val selectedId = isListDetailScene
                        .takeIf { it }
                        ?.let {
                            (navigationState.backStack
                                .lastOrNull { it is AppRoute.ScheduleEditor }
                                    as? AppRoute.ScheduleEditor)?.scheduleId
                        }
                    ScheduleListScreen(
                        selectedScheduleId = selectedId,
                        onEditSchedule = { scheduleId ->
                            navigator.popToAndPush(
                                anchor = key,
                                target = AppRoute.ScheduleEditor(scheduleId)
                            )
                        }
                    )
                }
                entry<AppRoute.Profiles>(
                    metadata = ListDetailSceneStrategy.listPane(sceneKey = "Profile")
                ) { key ->
                    val isListDetailScene = LocalListDetailSceneScope.current != null
                    val selectedId = isListDetailScene
                        .takeIf { it }
                        ?.let {
                            (navigationState.backStack
                                .lastOrNull { it is AppRoute.ProfileEditor }
                                    as? AppRoute.ProfileEditor)?.profileId
                        }
                    ProfileListScreen(
                        selectedProfileId = selectedId,
                        onEditProfile = { profileId ->
                            navigator.popToAndPush(
                                anchor = key,
                                target = AppRoute.ProfileEditor(profileId)
                            )
                        }
                    )
                }
                entry<AppRoute.ProfileEditor>(
                    metadata = ListDetailSceneStrategy.detailPane(sceneKey = "Profile")
                ) { key ->
                    val isListDetailScene = LocalListDetailSceneScope.current != null

                    val viewModel =
                        hiltViewModel<ProfileEditViewModel, ProfileEditViewModel.Factory> { factory ->
                            factory.create(key.profileId)
                        }
                    ResultEffect<AdapterConfigResult> { result ->
                        viewModel.handleAdapterConfigSubmitted(result)
                    }
                    ProfileEditScreen(
                        profileId = key.profileId,
                        isSinglePane = !isListDetailScene,
                        onBack = {
                            navigator.safeGoBack()
                        },
                        onDirtyChange = {
                            navigator.setRouteDirty(key, it)
                        },
                        onSaved = {
                            if (!isListDetailScene) {
                                navigator.forceGoBack()
                            }
                        },
                        onDeleted = {
                            navigator.forceGoBack()
                        },
                        onConfigureAdapter = { packageName, original ->
                            navigator.popToAndPush(
                                anchor = key,
                                target = AppRoute.AdapterConfigEditor(
                                    packageName = packageName,
                                    original = original
                                )
                            )
                        },
                        viewModel = viewModel
                    )
                }
                entry<AppRoute.ScheduleEditor>(
                    metadata = ListDetailSceneStrategy.detailPane(sceneKey = "Schedule")
                ) { key ->
                    val isListDetailScene = LocalListDetailSceneScope.current != null
                    ScheduleEditScreen(
                        scheduleId = key.scheduleId,
                        onBack = {
                            navigator.safeGoBack()
                        },
                        onSaved = {
                            if (!isListDetailScene) {
                                navigator.forceGoBack()
                            }
                        },
                        isSinglePane = !isListDetailScene,
                        onDirtyChange = {
                            navigator.setRouteDirty(key, it)
                        },
                        onDeleted = {
                            navigator.forceGoBack()
                        },
                        onProfileCreated = { profileId ->
                            navigator.navigate(AppRoute.ProfileEditor(profileId))
                        }
                    )
                }
                entry<AppRoute.AdapterConfigEditor>(
                    metadata = ListDetailSceneStrategy.extraPane(sceneKey = "Profile")
                ) { key ->
                    val isListDetailScene = LocalListDetailSceneScope.current != null
                    val resultBus = LocalResultEventBus.current
                    AdapterConfigScreen(
                        packageName = key.packageName,
                        original = key.original,
                        isSinglePane = !isListDetailScene,
                        onConfirm = { configInfo ->
                            resultBus.sendResult<AdapterConfigResult>(
                                result = AdapterConfigResult(
                                    packageName = key.packageName,
                                    config = configInfo
                                )
                            )
                            navigator.forceGoBack()
                        },
                        onCancel = {
                            navigator.safeGoBack()
                        },
                        onDirtyChange = {
                            navigator.setRouteDirty(key, it)
                        }
                    )
                }
            }
        )

        if (navigationState.showWarning) {
            AlertDialog(
                onDismissRequest = {
                    navigator.cancelDiscard()
                },
                title = {
                    Text(stringResource(R.string.unsaved_changes))
                },
                text = {
                    Text(stringResource(R.string.unsaved_changes_message))
                },
                confirmButton = {
                    TextButton(onClick = {
                        navigator.confirmDiscard()
                    }) {
                        Text(stringResource(R.string.discard_changes))
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        navigator.cancelDiscard()
                    }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
            )
        }


        BackHandler(enabled = navigationState.isTopRouteDirty) {
            navigator.safeGoBack()
        }

    }
}


sealed interface PendingNavigation {
    object GoBack : PendingNavigation
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
    val isTopRouteDirty: Boolean
        get() = backStack.lastOrNull()?.let { it in dirtyKeys } ?: false
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


class Navigator(
    val state: NavigationState
) {

    fun navigate(route: NavKey) {
        if (state.backStack.lastOrNull() == route) {
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
        if (state.backStack.lastOrNull() == route) {
            return
        }
        if (route is AppRoute.TopLevel) {
            state.backStack.subList(1, state.backStack.size).clear()
            state.topLevelRoute = route
        }
        state.backStack.add(route)
    }

    fun popToAndPush(anchor: NavKey, target: NavKey) {
        if (state.backStack.lastOrNull() == target) {
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
        if (state.backStack.lastOrNull() == target) {
            return
        }
        while (state.backStack.isNotEmpty() && state.backStack.lastOrNull() != anchor) {
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

    fun safeGoBack() {
        if (state.isTopRouteDirty) {
            state.pendingNavigation = PendingNavigation.GoBack
            state.showWarning = true
            return
        }
        forceGoBack()
    }

    fun forceGoBack() {
        val poppedKey = state.backStack.lastOrNull()
        if (poppedKey != null) {
            state.dirtyKeys -= poppedKey
        }
        state.showWarning = false
        state.backStack.removeLastOrNull()
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
                forceGoBack()
            }

            is PendingNavigation.Navigate -> {
                forceNavigate(action.target)
            }

            is PendingNavigation.PopToAndPush -> {
                forcePopToAndPush(action.anchor, action.target)
            }

            null -> {
                forceGoBack()
            }
        }
    }

    fun cancelDiscard() {
        state.showWarning = false
        state.pendingNavigation = null
    }
}