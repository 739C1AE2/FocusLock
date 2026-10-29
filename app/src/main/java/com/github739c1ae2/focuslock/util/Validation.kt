package com.github739c1ae2.focuslock.util

enum class ValidationSeverity { Error, Warning }

data class ValidationIssue<F>(
    val fields: Set<F>,
    val message: UiText,
    val severity: ValidationSeverity = ValidationSeverity.Error
) {
    init {
        require(fields.isNotEmpty())
    }
}

data class ValidationResult<F>(val issues: List<ValidationIssue<F>> = emptyList()) {
    val hasErrors: Boolean get() = issues.any { it.severity == ValidationSeverity.Error }
    fun issuesFor(field: F): List<ValidationIssue<F>> = issues.filter { field in it.fields }
    fun hasError(field: F): Boolean = issues.any {
        field in it.fields && it.severity == ValidationSeverity.Error
    }
    fun firstError(field: F): UiText? = issues.firstOrNull {
        field in it.fields && it.severity == ValidationSeverity.Error
    }?.message
}
