package io.github.inq_o.phonedoctor.feature.guardian.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.inq_o.phonedoctor.core.model.PHONE_NAME_MAX

/** 표시 이름 고르기의 선택 (O-G4·G4). 처음엔 아무것도 고르지 않는다 (ux-flows §4: 미리 고르지 않음) */
internal data class PhoneNameChoice(val preset: String? = null, val custom: Boolean = false, val text: String = "") {
    /** 저장할 이름. 고르지 않았거나 직접 입력이 비었으면 null */
    val value: String? get() = (if (custom) text else preset)?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * 상대 폰을 이 폰에서 부를 이름 (ux-flows §2·§4 O-G4). 고른 칩은 ✓와 테두리로도 보인다 (색만으로 구분하지 않음, §5)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhoneNamePicker(choice: PhoneNameChoice, onChange: (PhoneNameChoice) -> Unit, modifier: Modifier = Modifier) {
    val presets = listOf(
        stringResource(R.string.feature_guardian_impl_phone_name_mom),
        stringResource(R.string.feature_guardian_impl_phone_name_dad),
        stringResource(R.string.feature_guardian_impl_phone_name_grandma),
        stringResource(R.string.feature_guardian_impl_phone_name_grandpa),
    )
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.feature_guardian_impl_phone_name_legend), style = MaterialTheme.typography.labelLarge)
        FlowRow(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            presets.forEach { name ->
                NameChip(name, selected = !choice.custom && choice.preset == name) { onChange(PhoneNameChoice(preset = name)) }
            }
            NameChip(stringResource(R.string.feature_guardian_impl_phone_name_custom), selected = choice.custom) {
                onChange(choice.copy(preset = null, custom = true))
            }
        }
        if (choice.custom) {
            OutlinedTextField(
                value = choice.text,
                onValueChange = { onChange(choice.copy(text = it.take(PHONE_NAME_MAX))) },
                label = { Text(stringResource(R.string.feature_guardian_impl_phone_name_custom_label, PHONE_NAME_MAX)) },
                supportingText = { Text("${choice.text.length}/$PHONE_NAME_MAX") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun NameChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected, selectedBorderWidth = 2.dp),
        modifier = Modifier.heightIn(min = 48.dp).semantics { role = Role.RadioButton },
    )
}
