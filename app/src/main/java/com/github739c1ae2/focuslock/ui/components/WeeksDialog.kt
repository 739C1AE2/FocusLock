package com.github739c1ae2.focuslock.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github739c1ae2.focuslock.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WeeksDialog(
    totalWeeks: Int,
    initial: Set<Int>,
    onConfirm: (Set<Int>) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(initial) }
    val safeTotal = totalWeeks.coerceAtLeast(1)
    val allWeeks = (1..safeTotal).toSet()
    val oddWeeks = (1..safeTotal).filter { it % 2 == 1 }.toSet()
    val evenWeeks = (1..safeTotal).filter { it % 2 == 0 }.toSet()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.weeks)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selected == allWeeks,
                        onClick = { selected = allWeeks },
                        label = { Text(stringResource(R.string.weeks_all)) }
                    )
                    FilterChip(
                        selected = selected == oddWeeks,
                        onClick = { selected = oddWeeks },
                        label = { Text(stringResource(R.string.weeks_odd)) }
                    )
                    FilterChip(
                        selected = selected == evenWeeks,
                        onClick = { selected = evenWeeks },
                        label = { Text(stringResource(R.string.weeks_even)) }
                    )
                }
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    (1..safeTotal).forEach { week ->
                        FilterChip(
                            selected = week in selected,
                            onClick = {
                                selected = if (week in selected) selected - week else selected + week
                            },
                            label = { Text(week.toString()) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
