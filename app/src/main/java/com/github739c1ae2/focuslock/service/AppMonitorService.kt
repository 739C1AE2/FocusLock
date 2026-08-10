package com.github739c1ae2.focuslock.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.github739c1ae2.focuslock.database.LockRepository
import com.github739c1ae2.focuslock.engine.LockEngine
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class AppMonitorService : AccessibilityService() {

    companion object {
        private const val TAG = "LockService"
    }

    @Inject
    lateinit var repository: LockRepository

    lateinit var engine: LockEngine

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "无障碍服务已启动")
        engine = LockEngine(this, repository)
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