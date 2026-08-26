package com.github739c1ae2.focuslock.ui.screen.profile

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github739c1ae2.focuslock.util.sortTextBy
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class AppItemModel(val packageName: String, val appName: String, val isSystemApp: Boolean)

data class AppSelectionState(
    val isLoading: Boolean = true,
    val showAllApps: Boolean = false,
    val appList: List<AppItemModel> = emptyList(),
    val checkedPackages: Set<String> = emptySet()
)

@HiltViewModel
class AppPickerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val pm: PackageManager = context.packageManager

    val uiState: StateFlow<AppSelectionState>
        field = MutableStateFlow(AppSelectionState())

    init {
        loadApps()
    }

    fun resetSelection() {
        uiState.update { it.copy(checkedPackages = emptySet()) }
    }

    fun toggleShowAllApps() {
        uiState.update { it.copy(showAllApps = !it.showAllApps, isLoading = true) }
        loadApps()
    }

    fun toggleAppCheck(packageName: String) {
        val currentChecked = uiState.value.checkedPackages.toMutableSet()
        if (currentChecked.contains(packageName)) {
            currentChecked.remove(packageName)
        } else {
            currentChecked.add(packageName)
        }
        uiState.update { it.copy(checkedPackages = currentChecked) }
    }

    private fun loadApps() {
        viewModelScope.launch {
            val showAll = uiState.value.showAllApps
            val apps = withContext(Dispatchers.IO) {
                if (showAll) {
                    pm.getInstalledApplications(0)
                        .map { info ->
                            AppItemModel(
                                packageName = info.packageName,
                                appName = pm.getApplicationLabel(info).toString(),
                                isSystemApp = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
                            )
                        }
                        .distinctBy { it.packageName }
                        .sortTextBy { it.appName }
                } else {
                    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                        .map { resolveInfo ->
                            AppItemModel(
                                packageName = resolveInfo.activityInfo.packageName,
                                appName = resolveInfo.loadLabel(pm).toString(),
                                isSystemApp = resolveInfo.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
                            )
                        }
                        .distinctBy { it.packageName }
                        .sortTextBy { it.appName }
                }
            }
            uiState.update { it.copy(appList = apps, isLoading = false) }
        }
    }
}