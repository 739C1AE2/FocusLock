package com.github739c1ae2.focuslock.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.database.ProfileEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileDropdown(
    profiles: List<ProfileEntity>,
    selectedId: Long,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.profile_binding),
    onNewProfile: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = profiles.firstOrNull { it.id == selectedId }?.name ?: selectedId.toString()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            profiles.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.name) },
                    onClick = {
                        onSelect(profile.id)
                        expanded = false
                    }
                )
            }
            if (onNewProfile != null) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.create_profile)) },
                    onClick = {
                        expanded = false
                        onNewProfile()
                    }
                )
            }
        }
    }
}
