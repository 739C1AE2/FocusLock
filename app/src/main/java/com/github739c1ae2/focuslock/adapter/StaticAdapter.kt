package com.github739c1ae2.focuslock.adapter

import android.view.accessibility.AccessibilityNodeInfo

class StaticAdapter private constructor(override val currentLockState: AdapterLockState) : AppAdapter {
    override val requiresContentUpdate: Boolean = false

    override fun onAttach(packageName: String) {}

    override fun onDetach() {
    }

    override fun onEvent(
        activityName: String?,
        rootNode: AccessibilityNodeInfo?
    ) {
    }

    companion object {
        val PASSED = StaticAdapter(AdapterLockState.PASS)
        val BLOCKED = StaticAdapter(AdapterLockState.BLOCK)
    }
}