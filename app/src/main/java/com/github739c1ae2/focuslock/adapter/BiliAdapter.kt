package com.github739c1ae2.focuslock.adapter

import android.view.accessibility.AccessibilityNodeInfo
import com.github739c1ae2.focuslock.R

class BiliAdapter(
    config: Map<String, ConfigValue>
) : AppAdapter {

    object Factory : AppAdapterFactory<BiliAdapter> {
        override val adapterId: String = "bilibili_detail"
        override val adapterName: Int = R.string.app_adapter_name_bilibili
        override val description: Int = R.string.app_adapter_desc_bilibili

        override val configSchema: List<ConfigSpec> = listOf(
            ConfigSpec.StringList(
                key = "uploaders",
                titleRes = R.string.app_adapter_config_title_bilibili_uploaders,
                descRes = R.string.app_adapter_config_desc_bilibili_uploaders,
            ),
            ConfigSpec.StringList(
                key = "title_keywords",
                titleRes = R.string.app_adapter_config_title_bilibili_title_keywords,
                descRes = R.string.app_adapter_config_desc_bilibili_title_keywords,
            ),
            ConfigSpec.RadioGroup(
                key = "up_match_rule",
                titleRes = R.string.app_adapter_config_title_bilibili_up_match_rule,
                descRes = null,
                options = listOf(
                    ChoiceOption(
                        "contains",
                        R.string.app_adapter_config_choice_bilibili_up_match_contains
                    ),
                    ChoiceOption(
                        "exact",
                        R.string.app_adapter_config_choice_bilibili_up_match_exact
                    ),
                ),
                defaultSelectedKey = "contains",
            ),
            ConfigSpec.RadioGroup(
                key = "combo_rule",
                titleRes = R.string.app_adapter_config_title_bilibili_combo_rule,
                descRes = null,
                options = listOf(
                    ChoiceOption(
                        "or",
                        R.string.app_adapter_config_choice_bilibili_combo_or
                    ),
                    ChoiceOption(
                        "and",
                        R.string.app_adapter_config_choice_bilibili_combo_and
                    ),
                ),
                defaultSelectedKey = "or",
            ),
        )

        override fun canHandle(packageName: String): Boolean {
            return packageName == "tv.danmaku.bili" || packageName == "tv.danmaku.bilibilihd"
        }

        override fun create(config: Map<String, ConfigValue>): BiliAdapter {
            return BiliAdapter(config)
        }
    }

    @Volatile
    override var requiresContentUpdate: Boolean = true

    override var currentLockState: AdapterLockState = AdapterLockState.PASS

    private val upUsers = config.getStringList("uploaders")
    private val titleKeywords = config.getStringList("title_keywords")
    private val isUpExactMatch = config.getString("up_match_rule", "contains") == "exact"
    private val isComboAnd = config.getString("combo_rule", "or") == "and"

    private var packageName: String = "tv.danmaku.bili"

    // b站全屏播放时不会提供任何信息（有播放控件时可能会有视频标题，但那是分P的标题，不是总标题）
    // 因此我们只能缓存下来
    private var currentAuthor: String? = null
    private var currentTitle: String? = null

    data class ScreenData(val author: String?, val title: String?)

    override fun onAttach(packageName: String) {
        this.packageName = packageName
    }

    override fun onDetach() {
        currentAuthor = null
        currentTitle = null
    }

    override fun onEvent(
        activityName: String?,
        rootNode: AccessibilityNodeInfo?
    ) {
        if (upUsers.isEmpty() && titleKeywords.isEmpty()) {
            currentLockState = AdapterLockState.PASS
            requiresContentUpdate = false
            return
        }
        val isDetail = activityName?.endsWith("UnitedBizDetailsActivity")
            ?: (rootNode?.findFirstNodeByViewId("${packageName}:id/video_container") != null)
        if (!isDetail) {
            currentAuthor = null
            currentTitle = null
            currentLockState = AdapterLockState.PASS
            requiresContentUpdate = false
            return
        }
        extractScreen(rootNode).let { result ->
            if (result.author == null) {
                // UP主信息更容易获取，如果连 UP主都获取不到，那就说明失败了（如全屏播放时）
                return@let
            }
            if (result.author != currentAuthor) {
                currentTitle = null
            }
            if (result.title?.isNotEmpty() == true) {
                currentTitle = result.title
            }
            currentAuthor = result.author
        }

        currentLockState = decide()
        requiresContentUpdate = true
    }

    private fun decide(): AdapterLockState {
        val authorMatch = if (upUsers.isEmpty()) {
            isComboAnd
        } else if (isUpExactMatch) {
            upUsers.any { it == currentAuthor }
        } else {
            upUsers.any { currentAuthor?.contains(it) == true }
        }
        val titleMatch = if (titleKeywords.isEmpty()) {
            isComboAnd
        } else {
            titleKeywords.any { currentTitle?.contains(it) == true }
        }
        val allow = if (isComboAnd) {
            authorMatch && titleMatch
        } else {
            authorMatch || titleMatch
        }
        return if (allow) {
            AdapterLockState.PASS
        } else {
            AdapterLockState.BLOCK
        }
    }

    private fun extractScreen(rootNode: AccessibilityNodeInfo?): ScreenData {
        val detail = rootNode?.findFirstNodeByViewId("${packageName}:id/pager")
        if (detail != null) {
            val author =
                rootNode.findFirstNodeByViewId("${packageName}:id/author_name")?.text?.toString()
            val titles = detail.findAccessibilityNodeInfosByViewId("${packageName}:id/title")
            // 详情页下，第一个通常是主标题，但也可能是直播预约，直播预约的父节点的最后一个子节点是关闭按钮
            val title = titles.firstOrNull {
                it.parent?.childCount?.let { count ->
                    val child = it.parent.getChild(count - 1)
                    child?.viewIdResourceName != "${packageName}:id/close"
                } ?: true
            }
            val text = title?.text?.toString().orEmpty()
            val contentDesc = title?.contentDescription?.toString().orEmpty()
            return ScreenData(author, "$contentDesc $text".trim())
        }

        // 全屏但视频完成时，通常没有 author_name，当前视频的UP主是 name，推荐视频的是 author，
        // 如果再找不到那就只能说明是全屏播放无控件状态了，什么都没有，我们也无能为力
        val author =
            rootNode?.findFirstNodeByViewId("${packageName}:id/name")?.text?.toString()
        return ScreenData(author, null)
    }


    private fun AccessibilityNodeInfo.findFirstNodeByViewId(
        viewId: String
    ): AccessibilityNodeInfo? {
        return this.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull()
    }

}
