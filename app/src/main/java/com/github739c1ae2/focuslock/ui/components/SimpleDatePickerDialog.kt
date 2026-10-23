package com.github739c1ae2.focuslock.ui.components

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun SimpleDatePickerDialog(
    initialEpochDay: Long?,
    onConfirm: (epochDay: Long) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initialEpochDay?.let {
            LocalDate.ofEpochDay(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }
    )
    DatePickerDialog (
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { millis ->
                    val epochDay = Instant.ofEpochMilli(millis)
                        .atZone(ZoneOffset.UTC)
                        .toLocalDate()
                        .toEpochDay()
                    onConfirm(epochDay)
                }
                onDismiss()
            }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    ) {
        DatePicker(
            state = state,
            modifier = Modifier.verticalScroll(rememberScrollState())
        )
    }
}
