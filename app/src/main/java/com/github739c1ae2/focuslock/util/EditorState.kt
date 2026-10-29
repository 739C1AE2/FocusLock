package com.github739c1ae2.focuslock.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** The writable editor state contains inputs only; validation is always derived. */
internal sealed interface EditorState<out D> {
    data object Loading : EditorState<Nothing>
    data object Failed : EditorState<Nothing>
    data class Loaded<D>(val draft: D, val isDirty: Boolean = false) : EditorState<D>
}

internal fun <D> MutableStateFlow<EditorState<D>>.edit(transform: (D) -> D) {
    update { current ->
        if (current is EditorState.Loaded) {
            current.copy(draft = transform(current.draft), isDirty = true)
        } else current
    }
}

/** Read the writable source, not the asynchronously derived UI state, when submitting. */
internal fun <D, F> MutableStateFlow<EditorState<D>>.validatedSnapshot(
    validate: (D) -> ValidationResult<F>
): EditorState.Loaded<D>? {
    val snapshot = value as? EditorState.Loaded ?: return null
    return snapshot.takeUnless { validate(it.draft).hasErrors }
}

/** A completed write must never replace edits (or a reload) made while it was suspended. */
internal fun <D> MutableStateFlow<EditorState<D>>.markSaved(snapshot: EditorState.Loaded<D>): Boolean =
    value === snapshot && compareAndSet(snapshot, snapshot.copy(isDirty = false))
