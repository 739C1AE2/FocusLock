package com.github739c1ae2.focuslock.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.database.LockRepository
import com.github739c1ae2.focuslock.datastore.AppSettingsManager
import com.github739c1ae2.focuslock.engine.LockEngine
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@SuppressLint("AccessibilityPolicy")
@AndroidEntryPoint
class AppMonitorService : AccessibilityService() {

    companion object {
        private const val TAG = "LockService"
        private const val TIME_WINDOW_MS = 60_000L
        private const val MAX_ALLOWED_ERRORS = 3
    }

    @Inject
    lateinit var repository: LockRepository

    @Inject
    lateinit var settingsManager: AppSettingsManager

    private val errorTimestamps = ArrayDeque<Long>()

    private val mainHandler = Handler(Looper.getMainLooper())

    var engine: LockEngine? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "无障碍服务已启动")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "无障碍服务已连接")
        createNewEngine()
    }

    private fun createNewEngine() {
        engine?.destroy()
        engine = LockEngine(
            service = this, repository = repository, settingsManager = settingsManager,
            onError = { throwable ->
                mainHandler.post {
                    handleEngineError(throwable)
                }
            })
    }

    private fun handleEngineError(throwable: Throwable) {
        val currentTime = System.currentTimeMillis()

        Log.e(TAG, "引擎捕获到未处理的致命异常", throwable)

        while (!errorTimestamps.isEmpty() && (currentTime - errorTimestamps.first()) > TIME_WINDOW_MS) {
            errorTimestamps.removeFirst()
        }
        errorTimestamps.addLast(currentTime)

        if (errorTimestamps.size > MAX_ALLOWED_ERRORS) {
            Log.e(TAG, "引擎异常过于频繁，终止服务")
            Toast.makeText(
                this,
                R.string.engine_error_too_frequent,
                Toast.LENGTH_LONG
            ).show()
            // 之后 AccessibilityGuard 会尝试重启整个服务
            disableSelf()
            return
        }
        createNewEngine()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        engine?.dispatchAccessibilityEvent(event)
    }

    override fun onInterrupt() {
        Log.d(TAG, "无障碍服务被中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "无障碍服务正在关闭")
        engine?.destroy()
    }
}