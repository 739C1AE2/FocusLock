package com.github739c1ae2.focuslock.adapter

import android.view.accessibility.AccessibilityNodeInfo
import com.github739c1ae2.focuslock.R

class WeChatAdapter(
    config: Map<String, ConfigValue>
) : AppAdapter {

    object Factory : AppAdapterFactory<WeChatAdapter> {
        override val adapterId: String = "wechat"
        override val adapterName: Int = R.string.app_adapter_name_wechat
        override val description: Int = R.string.app_adapter_desc_wechat

        override val configSchema: List<ConfigSpec> = listOf(
            // 朋友圈
            ConfigSpec.Switch(
                key = "block_moments",
                titleRes = R.string.app_adapter_config_title_wechat_block_moments,
                defaultValue = false
            ),
            // 视频号
            ConfigSpec.Switch(
                key = "block_channels",
                titleRes = R.string.app_adapter_config_title_wechat_block_channels,
                defaultValue = false
            ),
            // 小程序
            ConfigSpec.Switch(
                key = "block_mini_programs",
                titleRes = R.string.app_adapter_config_title_wechat_block_mini_programs,
                defaultValue = false
            )
        )

        override fun canHandle(packageName: String): Boolean {
            return packageName == "com.tencent.mm"
        }

        override fun create(config: Map<String, ConfigValue>): WeChatAdapter {
            return WeChatAdapter(config)
        }
    }

    private val blockMoments: Boolean = config.getBoolean("block_moments", false)
    private val blockChannels: Boolean = config.getBoolean("block_channels", false)
    private val blockMiniPrograms: Boolean = config.getBoolean("block_mini_programs", false)

    override val requiresContentUpdate: Boolean = false
    override var currentLockState: AdapterLockState = AdapterLockState.PASS

    override fun onAttach(packageName: String) {
    }

    override fun onDetach() {
    }

    override fun onEvent(
        activityName: String?,
        rootNode: AccessibilityNodeInfo?
    ) {
        if (activityName == null) {
            currentLockState = AdapterLockState.PASS
            return
        }
        var block = false
        if (blockMoments && activityName
            .endsWith("com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI")) {
            block = true
        }
        if (blockChannels && activityName
            .endsWith("com.tencent.mm.plugin.finder.ui.FinderHomeAffinityUI")) {
            block = true
        }
        if (blockMiniPrograms && activityName
            .contains("com.tencent.mm.plugin.appbrand.ui.AppBrandUI")) {
            // AppBrandUI00 AppBrandUI01 ...
            block = true
        }
        currentLockState = if (block) AdapterLockState.BLOCK else AdapterLockState.PASS
    }
}