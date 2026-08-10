package com.github739c1ae2.focuslock.adapter

import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.StringRes


enum class AdapterLockState {
    PASS,
    BLOCK,
}


interface AppAdapter {

    /**
     * 当前适配器是否需要监听 TYPE_WINDOW_CONTENT_CHANGED，适配器可动态配置此值，以提高性能。
     */
    val requiresContentUpdate: Boolean

    /**
     * 引擎在判定当前 App 是否该锁时，直接读取这个属性。
     * 适配器通过分析缓存的类名和节点信息，实时更新此状态。
     */
    val currentLockState: AdapterLockState

    /**
     * 进入该应用
     */
    fun onAttach(packageName: String)

    /**
     * 离开该应用时
     */
    fun onDetach()

    /**
     * 接收到无障碍事件
     * @param activityName Activity 类名（可能为null）
     * @param rootNode 当前窗口的根节点
     */
    fun onEvent(activityName: String?, rootNode: AccessibilityNodeInfo?)

}

interface AppAdapterFactory<T: AppAdapter> {
    val configSchema: List<ConfigSpec>

    val adapterId: String

    @get:StringRes
    val adapterName: Int

    @get:StringRes
    val description: Int?

    fun canHandle(packageName: String) : Boolean

    fun create(config: Map<String, ConfigValue>) : T
}