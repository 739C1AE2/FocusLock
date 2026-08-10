package com.github739c1ae2.focuslock.adapter

import androidx.annotation.StringRes

data class ChoiceOption(
    val optionKey: String,
    @StringRes val displayRes: Int
)

sealed interface ConfigSpec {
    val key: String

    @get:StringRes
    val titleRes: Int

    @get:StringRes
    val descRes: Int?

    data class Switch(
        override val key: String,
        @StringRes override val titleRes: Int,
        @StringRes override val descRes: Int? = null,
        val defaultValue: Boolean = false
    ) : ConfigSpec

    data class StringList(
        override val key: String,
        @StringRes override val titleRes: Int,
        @StringRes override val descRes: Int? = null,
        @StringRes val hintRes: Int? = null,
    ) : ConfigSpec

    data class RadioGroup(
        override val key: String,
        @StringRes override val titleRes: Int,
        @StringRes override val descRes: Int? = null,
        val options: List<ChoiceOption>,
        val defaultSelectedKey: String? = null
    ) : ConfigSpec

    data class DropdownList(
        override val key: String,
        @StringRes override val titleRes: Int,
        @StringRes override val descRes: Int? = null,
        val options: List<ChoiceOption>,
        val defaultSelectedKey: String? = null
    ) : ConfigSpec
}