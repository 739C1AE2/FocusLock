package com.github739c1ae2.focuslock.adapter

import android.view.accessibility.AccessibilityNodeInfo
import com.github739c1ae2.focuslock.R

class GenericAdapter(
    config: Map<String, ConfigValue>
) : AppAdapter {

    object Factory : AppAdapterFactory<GenericAdapter> {
        override val adapterId: String = "generic_text"
        override val adapterName: Int = R.string.app_adapter_name_generic
        override val description: Int? = null

        override fun canHandle(packageName: String): Boolean {
            return true
        }

        override fun create(config: Map<String, ConfigValue>): GenericAdapter {
            return GenericAdapter(config)
        }

        override val configSchema: List<ConfigSpec> = listOf(
            ConfigSpec.DropdownList(
                key = "keyword_match_rule",
                titleRes = R.string.app_adapter_config_title_generic_keyword_match_rule,
                descRes = null,
                options = listOf(
                    ChoiceOption(
                        "all_contains",
                        R.string.app_adapter_config_choice_generic_all_contains
                    ),
                    ChoiceOption(
                        "part_contains",
                        R.string.app_adapter_config_choice_generic_part_contains
                    ),
                    ChoiceOption(
                        "all_not_contains",
                        R.string.app_adapter_config_choice_generic_all_not_contains
                    ),
                    ChoiceOption(
                        "part_or_any_contains",
                        R.string.app_adapter_config_choice_generic_part_or_any_contains
                    ),
                    ChoiceOption(
                        "part_or_all_not_contains",
                        R.string.app_adapter_config_choice_generic_part_or_all_not_contains
                    ),
                )
            ),
            ConfigSpec.StringList(
                key = "keywords",
                titleRes = R.string.app_adapter_config_title_generic_keywords,
                descRes = R.string.app_adapter_config_desc_generic_keywords,
                hintRes = null,
            ),
            ConfigSpec.RadioGroup(
                key = "logic_relation",
                titleRes = R.string.app_adapter_config_title_generic_logic_relation,
                descRes = null,
                options = listOf(
                    ChoiceOption("and", R.string.app_adapter_config_choice_generic_and),
                    ChoiceOption("or", R.string.app_adapter_config_choice_generic_or),
                )
            ),
            ConfigSpec.DropdownList(
                key = "activity_match_rule",
                titleRes = R.string.app_adapter_config_title_generic_activity_match_rule,
                descRes = null,
                options = listOf(
                    ChoiceOption("is", R.string.app_adapter_config_choice_generic_is),
                    ChoiceOption("not", R.string.app_adapter_config_choice_generic_not),
                )
            ),
            ConfigSpec.StringList(
                key = "activities",
                titleRes = R.string.app_adapter_config_title_generic_activities,
                descRes = null,
                hintRes = null,
            ),
            ConfigSpec.RadioGroup(
                key = "action",
                titleRes = R.string.app_adapter_config_title_generic_action,
                descRes = null,
                options = listOf(
                    ChoiceOption("block", R.string.app_adapter_config_choice_generic_block),
                    ChoiceOption("allow", R.string.app_adapter_config_choice_generic_allow),
                )
            ),
        )
    }


    @Volatile
    override var requiresContentUpdate: Boolean = false

    override var currentLockState: AdapterLockState = AdapterLockState.PASS

    private val keywords = config.getStringList("keywords")
    private val isAnd = config.getString("logic_relation", "and") == "and"
    private val keywordMatchRule = config.getString("keyword_match_rule", "all_contains")
    private val activityMatchRule = config.getString("activity_match_rule", "is") == "is"
    private val activities = config.getStringList("activities")
    private val isBlock = config.getString("action", "block") == "block"

    override fun onAttach(packageName: String) {
    }

    override fun onDetach() {
    }

    override fun onEvent(
        activityName: String?,
        rootNode: AccessibilityNodeInfo?
    ) {
        val hasKeywords = keywords.isNotEmpty() && rootNode != null
        val activityMatched = when {
            activities.isNotEmpty() && activityName != null -> checkActivity(activityName)
            else -> isAnd
        }
        val matched = when {
            isAnd && !activityMatched -> false
            !isAnd && activityMatched -> true
            hasKeywords -> checkKeywords(rootNode)
            else -> isAnd
        }
        currentLockState = if ((isBlock && matched) || (!isBlock && !matched)) {
            AdapterLockState.BLOCK
        } else {
            AdapterLockState.PASS
        }
        requiresContentUpdate = hasKeywords && if (isAnd) {
            activityMatched
        } else {
            !activityMatched
        }
    }

    private fun checkKeywords(
        node: AccessibilityNodeInfo
    ): Boolean {
        if (keywordMatchRule == "part_contains") {
            for (keyword in keywords) {
                if (node.findAccessibilityNodeInfosByText(keyword).isNotEmpty()) {
                    return true
                }
            }
            return false
        }
        var count = 0
        for (keyword in keywords) {
            if (node.findAccessibilityNodeInfosByText(keyword).isNotEmpty()) {
                count++
            }
        }
        return when (keywordMatchRule) {
            "all_contains" -> count == keywords.size
            "part_or_any_contains" -> count > 0
            "all_not_contains" -> count == 0
            "part_or_all_not_contains" -> count < keywords.size
            else -> false
        }
    }

    private fun checkActivity(
        activity: String
    ): Boolean {
        for (act in activities) {
            if (activity.endsWith(act)) {
                return activityMatchRule
            }
        }
        return !activityMatchRule
    }

}