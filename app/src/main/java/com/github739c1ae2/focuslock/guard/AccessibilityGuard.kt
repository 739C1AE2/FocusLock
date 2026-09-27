package com.github739c1ae2.focuslock.guard

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.service.AppMonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds


object AccessibilityGuard {

    private const val TAG = "AccessibilityGuard"
    private const val DEBOUNCE_MS = 2000L
    private const val TIME_WINDOW_MS = 90_000L
    private const val MAX_ALLOWED_RESTARTS = 5
    private val stopTimestamps = ArrayDeque<Long>()

    private lateinit var appContext: Context

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var featureJob: Job? = null
    private var ensureJob: Job? = null

    private var enabled = false

    private val mainHandler =
        Handler(Looper.getMainLooper())

    private val ensureRunnable = Runnable {
        triggerEnsure()
    }

    private lateinit var accessibilityManager: AccessibilityManager

    private val servicesStateListener =
        AccessibilityManager.AccessibilityServicesStateChangeListener {
            requestEnsure()
        }

    private val globalStateListener =
        AccessibilityManager.AccessibilityStateChangeListener {
            requestEnsure()
        }

    private val secureSettingsObserver =
        object : ContentObserver(mainHandler) {
            override fun onChange(
                selfChange: Boolean,
                uri: Uri?
            ) {
                requestEnsure()
            }
        }


    fun start(
        context: Context,
        enabledFlow: Flow<Boolean>
    ) {
        appContext = context.applicationContext
        accessibilityManager = appContext.getSystemService(AccessibilityManager::class.java)

        featureJob?.cancel()
        featureJob = scope.launch {
            enabledFlow.collectLatest { shouldEnable ->
                if (shouldEnable) {
                    startGuarding()
                } else {
                    stopGuarding()
                }
            }
        }
    }

    private fun startGuarding() {
        if (enabled) {
            requestEnsure()
            return
        }

        enabled = true
        registerListeners()
        requestEnsure()
    }

    private fun stopGuarding() {
        if (!enabled) {
            return
        }

        enabled = false
        mainHandler.removeCallbacks(ensureRunnable)
        unregisterListeners()
    }

    private fun registerListeners() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            accessibilityManager
                .addAccessibilityServicesStateChangeListener(
                    appContext.mainExecutor,
                    servicesStateListener
                )
        }
        accessibilityManager
            .addAccessibilityStateChangeListener(
                globalStateListener
            )

        // Secure Settings 变化作为补充信号
        val resolver = appContext.contentResolver
        resolver.registerContentObserver(
            Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_ENABLED
            ),
            false,
            secureSettingsObserver
        )
        resolver.registerContentObserver(
            Settings.Secure.getUriFor(
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ),
            false,
            secureSettingsObserver
        )
    }

    private fun unregisterListeners() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            accessibilityManager
                .removeAccessibilityServicesStateChangeListener(
                    servicesStateListener
                )
        }

        accessibilityManager
            .removeAccessibilityStateChangeListener(
                globalStateListener
            )
        appContext.contentResolver
            .unregisterContentObserver(
                secureSettingsObserver
            )
    }

    fun requestEnsure() {
        if (!enabled) {
            return
        }

        mainHandler.removeCallbacks(ensureRunnable)
        mainHandler.postDelayed(
            ensureRunnable,
            DEBOUNCE_MS
        )
    }

    private fun getA11yServiceInfoCrashedValue(info: AccessibilityServiceInfo): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            // 没有 AccessibilityServiceInfo.crashed 字段
            return null
        }
        val p = Parcel.obtain()
        return try {
            info.writeToParcel(p, 0)
            p.setDataPosition(0)

            require(p.readInt() == info.eventTypes)
            p.createStringArray() // packageNames
            require(p.readInt() == info.feedbackType)
            require(p.readLong() == info.notificationTimeout)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10 (API 29) 新增
                p.readInt() // mNonInteractiveUiTimeout
                p.readInt() // mInteractiveUiTimeout
            }
            require(p.readInt() == info.flags)

            val crashed = p.readInt()
            require(crashed == 0 || crashed == 1)
            crashed != 0
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read AccessibilityServiceInfo.crashed", e)
            null
        } finally {
            p.recycle()
        }
    }

    private fun triggerEnsure() {
        if (!enabled) {
            return
        }
        if (ensureJob?.isCompleted == false) {
            return
        }
        if (!hasWriteSecureSettingsPermission()) {
            Log.i(TAG, "WRITE_SECURE_SETTINGS not granted")
            return
        }
        ensureJob = scope.launch(Dispatchers.IO) {
            try {
                ensureA11yServiceEnabled()
            } catch (e: SecurityException) {
                Log.w(TAG, "读写 Secure Settings 失败", e)
            } catch (e: Exception) {
                Log.e(TAG, "修复无障碍服务状态失败", e)
            }
        }
    }

    fun hasWriteSecureSettingsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun ensureA11yServiceEnabled() {
        val resolver = appContext.contentResolver
        val component = ComponentName(
            appContext,
            AppMonitorService::class.java
        ).flattenToString()

        Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)

        val installedServices = accessibilityManager.installedAccessibilityServiceList
        val crashed = installedServices.firstOrNull { info ->
            val serviceInfo = info.resolveInfo.serviceInfo
            serviceInfo.packageName == appContext.packageName
                    && serviceInfo.name == AppMonitorService::class.java.name
        }?.let {
            getA11yServiceInfoCrashedValue(it)
        }

        val services =
            Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty()
                .split(':')
                .filter { it.isNotBlank() }
                .toMutableSet()

        if (crashed == true) {
            Log.w(TAG, "无障碍服务被系统标记为 crashed，尝试移除后重新添加")
            services.remove(component)
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                services.joinToString(":")
            )
            delay(1000.milliseconds)
        }

        if (services.add(component)) {
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                services.joinToString(":")
            )
            Log.i(TAG, "成功添加无障碍服务: $component")
            recordUnexpectedStop()
        }
    }

    private fun recordUnexpectedStop() {
        val currentTime = System.currentTimeMillis()

        while (!stopTimestamps.isEmpty() && (currentTime - stopTimestamps.first()) > TIME_WINDOW_MS) {
            stopTimestamps.removeFirst()
        }
        stopTimestamps.addLast(currentTime)

        if (stopTimestamps.size > MAX_ALLOWED_RESTARTS) {
            Log.e(TAG, "无障碍服务意外停止过于频繁，停止守护")
            mainHandler.post {
                Toast.makeText(
                    appContext,
                    R.string.a11y_service_stop_too_frequent,
                    Toast.LENGTH_LONG
                ).show()
                stopGuarding()
            }
        }
    }
}