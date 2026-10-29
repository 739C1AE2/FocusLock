package com.github739c1ae2.focuslock.ui.screen.profile

import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.util.UiText
import com.github739c1ae2.focuslock.util.ValidationIssue
import com.github739c1ae2.focuslock.util.ValidationResult

enum class ProfileField { Name }

fun validateProfile(draft: ProfileDraft): ValidationResult<ProfileField> = ValidationResult(
    if (draft.name.isBlank()) listOf(ValidationIssue(
        setOf(ProfileField.Name), UiText.StringResource(R.string.profile_name_empty_error)
    )) else emptyList()
)

