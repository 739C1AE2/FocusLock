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
            return packageName == "tv.danmaku.bili"
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

    // b站全屏播放时不会提供任何信息（有播放控件时可能会有视频标题，但那是分P的标题，不是总标题）
    // 因此我们只能缓存下来
    private var currentAuthor: String? = null
    private var currentTitle: String? = null

    sealed interface ScreenData {
        object FullScreen : ScreenData
        data class Detail(val author: String, val title: String) : ScreenData
        data class FullScreenComplete(val author: String) : ScreenData
    }

    override fun onAttach(packageName: String) {
    }

    override fun onDetach() {
        currentAuthor = null
        currentTitle = null
    }

    override fun onEvent(
        activityName: String?,
        rootNode: AccessibilityNodeInfo?
    ) {
        val isDetail = activityName?.endsWith("UnitedBizDetailsActivity")
            ?: (findFirstNodeByViewId(rootNode, "tv.danmaku.bili:id/video_container") != null)
        if (!isDetail) {
            currentAuthor = null
            currentTitle = null
            currentLockState = AdapterLockState.PASS
            requiresContentUpdate = false
            return
        }
        when (val result = extractScreen(rootNode)) {
            is ScreenData.FullScreen -> {
                // 继续使用之前的缓存
            }

            is ScreenData.Detail -> {
                if (result.author != currentAuthor) {
                    currentTitle = null
                }
                if (result.title.isNotEmpty()) {
                    currentTitle = result.title
                }
                currentAuthor = result.author
            }

            is ScreenData.FullScreenComplete -> {
                if (result.author != currentAuthor) {
                    currentTitle = null
                }
                currentAuthor = result.author
            }
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
        var author =
            findFirstNodeByViewId(rootNode, "tv.danmaku.bili:id/author_name")?.text?.toString()
        if (author == null) {
            // 全屏但视频完成时，没有 author_name，当前视频的UP主是 name，推荐视频的是 author
            author = findFirstNodeByViewId(rootNode, "tv.danmaku.bili:id/name")?.text?.toString()
            return if (author != null) {
                ScreenData.FullScreenComplete(author)
            } else {
                ScreenData.FullScreen
            }
        }

        // 详情页下，第一个通常是主标题
        val title = findFirstNodeByViewId(rootNode, "tv.danmaku.bili:id/title")
        val text = title?.text?.toString().orEmpty()
        val contentDesc = title?.contentDescription?.toString().orEmpty()
        return ScreenData.Detail(author, "$contentDesc $text".trim())
    }

    private fun findFirstNodeByViewId(
        rootNode: AccessibilityNodeInfo?,
        viewId: String
    ): AccessibilityNodeInfo? {
        return rootNode?.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull()
    }

}
