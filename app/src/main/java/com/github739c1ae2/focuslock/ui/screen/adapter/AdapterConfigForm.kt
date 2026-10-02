package com.github739c1ae2.focuslock.ui.screen.adapter

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.adapter.ChoiceOption
import com.github739c1ae2.focuslock.adapter.ConfigSpec
import com.github739c1ae2.focuslock.adapter.ConfigValue


@Composable
fun AdapterConfigForm(
    specs: List<ConfigSpec>,
    values: Map<String, ConfigValue>,
    onValueChange: (Map<String, ConfigValue>) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        specs.forEach { spec ->
            DynamicFieldDispatcher(
                spec = spec,
                value = values[spec.key],
                onValueChange = { newValue ->
                    onValueChange(values + (spec.key to newValue))
                }
            )
        }
    }
}
@Composable
fun DynamicFieldDispatcher(
    spec: ConfigSpec,
    value: ConfigValue?,
    onValueChange: (ConfigValue) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        val title = stringResource(id = spec.titleRes)
        val desc = spec.descRes?.let { stringResource(id = it) }
        when (spec) {
            is ConfigSpec.Switch -> {
                val checked = (value as? ConfigValue.BooleanValue)?.value ?: spec.defaultValue
                SwitchField(
                    title = title,
                    desc = desc,
                    checked = checked,
                    onCheckedChange = { onValueChange(ConfigValue.BooleanValue(it)) }
                )
            }
            is ConfigSpec.RadioGroup -> {
                val selectedKey = (value as? ConfigValue.StringValue)?.value ?: spec.defaultSelectedKey
                RadioGroupField(
                    title = title,
                    desc = desc,
                    options = spec.options,
                    selectedKey = selectedKey,
                    onOptionSelected = { onValueChange(ConfigValue.StringValue(it)) }
                )
            }
            is ConfigSpec.DropdownList -> {
                val selectedKey = (value as? ConfigValue.StringValue)?.value ?: spec.defaultSelectedKey
                DropdownField(
                    title = title,
                    desc = desc,
                    options = spec.options,
                    selectedKey = selectedKey,
                    onOptionSelected = { onValueChange(ConfigValue.StringValue(it)) }
                )
            }
            is ConfigSpec.StringList -> {
                val list = (value as? ConfigValue.StringListValue)?.value ?: emptyList()
                StringListField(
                    title = title,
                    desc = desc,
                    list = list,
                    hintRes = spec.hintRes,
                    onListChange = { onValueChange(ConfigValue.StringListValue(it)) }
                )
            }
        }
    }
}
@Composable
fun SwitchField(
    title: String,
    desc: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            if (desc != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun FieldHeader(title: String, desc: String?) {
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium
        )
        if (desc != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun RadioGroupField(
    title: String,
    desc: String?,
    options: List<ChoiceOption>,
    selectedKey: String?,
    onOptionSelected: (String) -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        FieldHeader(title, desc)

        options.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOptionSelected(option.optionKey) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = (option.optionKey == selectedKey),
                    onClick = { onOptionSelected(option.optionKey) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(id = option.displayRes))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownField(
    title: String,
    desc: String?,
    options: List<ChoiceOption>,
    selectedKey: String?,
    onOptionSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedOption = options.find { it.optionKey == selectedKey }
    val displayText = selectedOption?.let { stringResource(id = it.displayRes) } ?: ""

    Column(modifier = Modifier.padding(16.dp)) {
        FieldHeader(title, desc)

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = displayText,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(stringResource(id = option.displayRes)) },
                        onClick = {
                            onOptionSelected(option.optionKey)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun StringListField(
    title: String,
    desc: String?,
    list: List<String>,
    hintRes: Int?,
    onListChange: (List<String>) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        FieldHeader(title, desc)

        list.forEachIndexed { index, itemText ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = itemText,
                    onValueChange = { newText ->
                        val newList = list.toMutableList().apply { set(index, newText) }
                        onListChange(newList)
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = hintRes?.let { { Text(stringResource(id = it)) } }
                )
                IconButton(
                    onClick = {
                        val newList = list.toMutableList().apply { removeAt(index) }
                        onListChange(newList)
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.content_desc_remove_item)
                    )
                }
            }
        }

        Button(
            onClick = {
                val newList = list.toMutableList().apply { add("") }
                onListChange(newList)
            },
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text(stringResource(R.string.add_item))
        }
    }
}