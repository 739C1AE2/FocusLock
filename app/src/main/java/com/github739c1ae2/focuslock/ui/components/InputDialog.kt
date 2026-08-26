package com.github739c1ae2.focuslock.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction

@Composable
fun InputDialog(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    label: String? = null,
    supportingText: String? = null,
    confirmText: String = stringResource(android.R.string.ok),
    dismissText: String = stringResource(android.R.string.cancel),
    singleLine: Boolean = true,
    isError: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = {
            Text(text = title)
        },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                isError = isError,
                singleLine = singleLine,
                placeholder = placeholder?.let {
                    { Text(it) }
                },
                label = label?.let {
                    { Text(it) }
                },
                supportingText = supportingText?.let {
                    { Text(it) }
                },
                keyboardOptions = KeyboardOptions(
                    imeAction = if (singleLine) {
                        ImeAction.Done
                    } else {
                        ImeAction.Default
                    }
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (singleLine) {
                            onConfirm()
                        }
                    }
                )
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !isError
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text(dismissText)
            }
        }
    )
}