package com.github739c1ae2.focuslock.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.util.Log
import android.view.accessibility.AccessibilityEvent
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
    }

    @Inject
    lateinit var repository: LockRepository

    @Inject
    lateinit var settingsManager: AppSettingsManager

    lateinit var engine: LockEngine

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "无障碍服务已启动")
        engine = LockEngine(this, repository, settingsManager)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        engine.dispatchAccessibilityEvent(event)
    }

    override fun onInterrupt() {
        Log.d(TAG, "无障碍服务被中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "无障碍服务正在关闭")
        engine.destroy()
    }
}