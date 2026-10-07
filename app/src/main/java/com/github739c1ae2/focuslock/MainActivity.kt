package com.github739c1ae2.focuslock

import android.app.ActivityManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDragHandle
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.runtime.result.LocalResultEventBus
import androidx.navigation3.runtime.result.ResultEffect
import androidx.navigation3.runtime.result.rememberResultEventBusNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.window.core.layout.WindowSizeClass
import com.github739c1ae2.focuslock.datastore.AppSettingsManager
import com.github739c1ae2.focuslock.ui.navigation.AppRoute
import com.github739c1ae2.focuslock.ui.navigation.NAV_ITEMS
import com.github739c1ae2.focuslock.ui.navigation.Navigator
import com.github739c1ae2.focuslock.ui.navigation.rememberNavigationState
import com.github739c1ae2.focuslock.ui.navigation.scene.AdaptiveDialogSceneStrategy
import com.github739c1ae2.focuslock.ui.navigation.scene.rememberAdaptiveDialogSceneStrategy
import com.github739c1ae2.focuslock.ui.navigation.scene.rememberBackInterceptionSceneDecoratorStrategy
import com.github739c1ae2.focuslock.ui.screen.adapter.AdapterConfigScreen
import com.github739c1ae2.focuslock.ui.screen.course.CourseEditorScreen
import com.github739c1ae2.focuslock.ui.screen.course.CourseListScreen
import com.github739c1ae2.focuslock.ui.screen.course.CourseTableSettingsScreen
import com.github739c1ae2.focuslock.ui.screen.course.TimeTableEditorScreen
import com.github739c1ae2.focuslock.ui.screen.home.HomeScreen
import com.github739c1ae2.focuslock.ui.screen.profile.AdapterConfigResult
import com.github739c1ae2.focuslock.ui.screen.profile.ProfileEditScreen
import com.github739c1ae2.focuslock.ui.screen.profile.ProfileEditViewModel
import com.github739c1ae2.focuslock.ui.screen.profile.ProfileListScreen
import com.github739c1ae2.focuslock.ui.screen.schedule.ScheduleEditScreen
import com.github739c1ae2.focuslock.ui.screen.schedule.ScheduleListScreen
import com.github739c1ae2.focuslock.ui.screen.settings.LicensesScreen
import com.github739c1ae2.focuslock.ui.screen.settings.SettingsScreen
import com.github739c1ae2.focuslock.ui.theme.FocusLockTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsManager: AppSettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch {
            settingsManager.hideFromRecentsEnabled.collect { hideFromRecents ->
                val activityManager = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
                val appTasks = activityManager?.appTasks
                if (!appTasks.isNullOrEmpty()) {
                    appTasks[0].setExcludeFromRecents(hideFromRecents)
                }
            }
        }

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
    val activity = LocalActivity.current!!
    val navigator = remember(navigationState) { Navigator(navigationState, activity) }
    val listDetailStrategy: ListDetailSceneStrategy<NavKey> =  rememberListDetailSceneStrategy(
        paneExpansionDragHandle = { state ->
            val interactionSource = remember { MutableInteractionSource() }
            VerticalDragHandle(
                modifier =
                    Modifier.paneExpansionDraggable(
                        state,
                        LocalMinimumInteractiveComponentSize.current,
                        interactionSource,
                    ),
                interactionSource = interactionSource,
            )
        }
    )
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
                navigator.forceGoBack(null)
            },
            sceneStrategies = listOf(
                listDetailStrategy,
                rememberAdaptiveDialogSceneStrategy()
            ),
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
                rememberResultEventBusNavEntryDecorator()
            ),
            sceneDecoratorStrategies = listOf(
                rememberBackInterceptionSceneDecoratorStrategy(navigator)
            ),
            entryProvider = entryProvider {
                entry<AppRoute.Home> {
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
                        },
                        onOpenCourseTable = {
                            navigator.popToAndPush(
                                anchor = key,
                                target = AppRoute.CourseTable
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
                entry<AppRoute.Settings>(
                    metadata = ListDetailSceneStrategy.listPane(sceneKey = "Settings")
                ) {
                    val isListDetailScene = LocalListDetailSceneScope.current != null
                    val selectedRoute = isListDetailScene
                        .takeIf { it }
                        ?.let {
                            (navigationState.backStack
                                .lastOrNull { it is AppRoute.SettingsDetail }
                                    as? AppRoute.SettingsDetail)
                        }
                    SettingsScreen(
                        selectedRoute = selectedRoute,
                        onNavigateTo = { route ->
                            navigator.popToAndPush(
                                anchor = AppRoute.Settings,
                                target = route
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
                            navigator.safeGoBack(key)
                        },
                        onDirtyChange = {
                            navigator.setRouteDirty(key, it)
                        },
                        onSaved = {
                            if (!isListDetailScene) {
                                navigator.forceGoBack(key)
                            }
                        },
                        onDeleted = {
                            navigator.forceGoBack(key)
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
                            navigator.safeGoBack(key)
                        },
                        onSaved = {
                            if (!isListDetailScene) {
                                navigator.forceGoBack(key)
                            }
                        },
                        isSinglePane = !isListDetailScene,
                        onDirtyChange = {
                            navigator.setRouteDirty(key, it)
                        },
                        onDeleted = {
                            navigator.forceGoBack(key)
                        },
                        onProfileCreated = { profileId ->
                            navigator.navigate(AppRoute.ProfileEditor(profileId))
                        }
                    )
                }
                entry<AppRoute.CourseTable>(
                    metadata = ListDetailSceneStrategy.detailPane(sceneKey = "Schedule")
                ) { key ->
                    val isSinglePane = LocalListDetailSceneScope.current == null
                    CourseListScreen(
                        isSinglePane = isSinglePane,
                        onBack = { navigator.safeGoBack(key) },
                        onEditCourse = { courseId ->
                            navigator.popToAndPush(
                                anchor = key,
                                target = AppRoute.CourseEditor(courseId)
                            )
                        },
                        onOpenSettings = {
                            navigator.popToAndPush(
                                anchor = key,
                                target = AppRoute.CourseTableSettings
                            )
                        }
                    )
                }
                entry<AppRoute.CourseEditor>(
                    metadata = ListDetailSceneStrategy.detailPane(sceneKey = "Schedule")
                ) { key ->
                    val isListDetailScene = LocalListDetailSceneScope.current != null
                    CourseEditorScreen(
                        courseId = key.courseId,
                        isSinglePane = !isListDetailScene,
                        onBack = { navigator.safeGoBack(key) },
                        onDirtyChange = { navigator.setRouteDirty(key, it) },
                        onSaved = {
                            if (!isListDetailScene) {
                                navigator.forceGoBack(key)
                            }
                        },
                        onDeleted = { navigator.forceGoBack(key) },
                        onProfileCreated = { profileId ->
                            navigator.navigate(AppRoute.ProfileEditor(profileId))
                        }
                    )
                }
                entry<AppRoute.CourseTableSettings>(
                    metadata = ListDetailSceneStrategy.detailPane(sceneKey = "Schedule")
                ) { key ->
                    val isListDetailScene = LocalListDetailSceneScope.current != null
                    CourseTableSettingsScreen(
                        isSinglePane = !isListDetailScene,
                        onBack = { navigator.safeGoBack(key) },
                        onDirtyChange = { navigator.setRouteDirty(key, it) },
                        onSaved = {
                            if (!isListDetailScene) {
                                navigator.forceGoBack(key)
                            }
                        },
                        onOpenTimeTable = { timeTableId ->
                            navigator.popToAndPush(
                                anchor = key,
                                target = AppRoute.TimeTableEditor(timeTableId)
                            )
                        }
                    )
                }
                entry<AppRoute.TimeTableEditor>(
                    metadata = ListDetailSceneStrategy.detailPane(sceneKey = "Schedule")
                ) { key ->
                    val isListDetailScene = LocalListDetailSceneScope.current != null
                    TimeTableEditorScreen(
                        timeTableId = key.timeTableId,
                        isSinglePane = !isListDetailScene,
                        onBack = { navigator.safeGoBack(key) },
                        onDirtyChange = { navigator.setRouteDirty(key, it) },
                        onSaved = {
                            if (!isListDetailScene) {
                                navigator.forceGoBack(key)
                            }
                        },
                        onDeleted = { navigator.forceGoBack(key) }
                    )
                }
                entry<AppRoute.AdapterConfigEditor>(
                    metadata = AdaptiveDialogSceneStrategy.dialog(
                        // FIXME: BackInterceptionSceneDecoratorStrategy 无法拦截对话框模式下的返回，
                        //  这里先完全阻止返回键和空白处关闭，来避免用户误触导致丢失。
                        //  SceneDecoratorStrategyScope<T>.decorateScene: this does not apply to
                        //  OverlayScene because they are animated separately from non-overlay scenes.
                        DialogProperties(
                            dismissOnBackPress = false,
                            dismissOnClickOutside = false
                        )
                    )
                ) { key ->
                    val resultBus = LocalResultEventBus.current
                    AdapterConfigScreen(
                        packageName = key.packageName,
                        original = key.original,
                        onConfirm = { configInfo ->
                            resultBus.sendResult<AdapterConfigResult>(
                                result = AdapterConfigResult(
                                    packageName = key.packageName,
                                    config = configInfo
                                )
                            )
                            navigator.forceGoBack(key)
                        },
                        onCancel = { force ->
                            if (force) {
                                navigator.forceGoBack(key)
                            } else {
                                navigator.safeGoBack(key)
                            }
                        },
                        onDirtyChange = {
                            navigator.setRouteDirty(key, it)
                        }
                    )
                }
                entry<AppRoute.Licenses>(
                    metadata = ListDetailSceneStrategy.detailPane(sceneKey = "Settings")
                ) {
                    val isSinglePane = LocalListDetailSceneScope.current == null
                    LicensesScreen(
                        isSinglePane = isSinglePane,
                        onBack = {
                            navigator.safeGoBack(it)
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
    }
}
