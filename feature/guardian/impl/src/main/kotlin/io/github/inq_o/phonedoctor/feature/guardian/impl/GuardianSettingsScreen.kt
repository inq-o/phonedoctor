package io.github.inq_o.phonedoctor.feature.guardian.impl

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.inq_o.phonedoctor.core.data.DISPLAY_NAME_MAX
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianOutlinedButton
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianPrimaryButton
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianScreen
import io.github.inq_o.phonedoctor.core.designsystem.theme.GuardianTheme
import io.github.inq_o.phonedoctor.core.designsystem.theme.PhoneDoctorTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * @param onDeleted 모든 데이터를 지움 → 역할 선택부터
 */
@Composable
fun GuardianSettingsScreen(
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GuardianSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 지우는 도중 뒤로 가면 화면과 함께 작업이 취소돼 반쯤 지운 채 남는다
    BackHandler(enabled = state.working) {}
    LaunchedEffect(state.deleted) {
        if (state.deleted) {
            viewModel.consumeDeleted()
            onDeleted()
        }
    }
    GuardianSettingsScreen(
        state = state,
        onNotifyCaution = viewModel::setNotifyCaution,
        onFalsePositiveFeedback = viewModel::setFalsePositiveFeedback,
        onRename = viewModel::rename,
        onDeleteAll = viewModel::deleteAll,
        onReauth = {
            if (viewModel.startReauth()) {
                scope.launch {
                    val token = try {
                        requestGoogleIdToken(context)
                    } catch (e: CancellationException) {
                        viewModel.onReauthFailed(e)
                        throw e
                    } catch (e: Exception) {
                        viewModel.onReauthFailed(e)
                        return@launch
                    }
                    viewModel.onReauthToken(token)
                }
            }
        },
        onBack = onBack,
        modifier = modifier,
    )
}

/** G4 설정 */
@Composable
internal fun GuardianSettingsScreen(
    state: GuardianSettingsUiState,
    onNotifyCaution: (Boolean) -> Unit,
    onRename: (String) -> Unit,
    onFalsePositiveFeedback: (Boolean) -> Unit = {},
    onDeleteAll: () -> Unit,
    onReauth: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    GuardianScreen(
        title = stringResource(R.string.feature_guardian_impl_settings_title),
        modifier = modifier,
        actions = {
            if (state.needsReauth) GuardianPrimaryButton(stringResource(R.string.feature_guardian_impl_settings_reauth), onReauth, enabled = !state.working)
            GuardianOutlinedButton(stringResource(R.string.feature_guardian_impl_back), onBack, enabled = !state.working)
        },
    ) {
        state.error?.let { ErrorText(it.message()) }
        if (state.needsReauth) ErrorText(stringResource(R.string.feature_guardian_impl_settings_reauth_body))
        if (state.wrongAccount) ErrorText(stringResource(R.string.feature_guardian_impl_settings_reauth_wrong_account))
        if (state.saved) Body(stringResource(R.string.feature_guardian_impl_settings_saved))
        if (state.working) CircularProgressIndicator()
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SwitchRow(
                title = stringResource(R.string.feature_guardian_impl_settings_caution),
                body = stringResource(R.string.feature_guardian_impl_settings_caution_body),
                checked = state.notifyCaution,
                enabled = !state.working,
                onChange = onNotifyCaution,
            )
            HorizontalDivider()
            SwitchRow(
                title = stringResource(R.string.feature_guardian_impl_settings_feedback),
                body = stringResource(R.string.feature_guardian_impl_settings_feedback_body),
                checked = state.falsePositiveFeedback,
                enabled = true,
                onChange = onFalsePositiveFeedback,
            )
            HorizontalDivider()
            Text(stringResource(R.string.feature_guardian_impl_settings_name), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Body(
                stringResource(
                    R.string.feature_guardian_impl_settings_name_body,
                    state.displayName ?: stringResource(R.string.feature_guardian_impl_settings_name_default),
                ),
            )
            GuardianOutlinedButton(stringResource(R.string.feature_guardian_impl_settings_name_change), { renaming = true }, enabled = !state.working)
            HorizontalDivider()
            Text(stringResource(R.string.feature_guardian_impl_settings_delete_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Body(stringResource(R.string.feature_guardian_impl_settings_delete_body))
            GuardianOutlinedButton(stringResource(R.string.feature_guardian_impl_settings_delete), { confirmingDelete = true }, enabled = !state.working)
            Spacer(Modifier.height(8.dp))
        }
    }
    if (renaming) {
        var name by rememberSaveable { mutableStateOf(state.displayName.orEmpty()) }
        GuardianTheme {
            AlertDialog(
                onDismissRequest = { renaming = false },
                title = { Text(stringResource(R.string.feature_guardian_impl_settings_name)) },
                text = {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(DISPLAY_NAME_MAX) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.feature_guardian_impl_settings_name_label)) },
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            renaming = false
                            onRename(name)
                        },
                        enabled = name.trim().length in 1..DISPLAY_NAME_MAX,
                    ) { Text(stringResource(R.string.feature_guardian_impl_settings_name_save)) }
                },
                dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.feature_guardian_impl_keep)) } },
            )
        }
    }
    if (confirmingDelete) {
        GuardianTheme {
            AlertDialog(
                onDismissRequest = { confirmingDelete = false },
                title = { Text(stringResource(R.string.feature_guardian_impl_settings_delete_dialog_title)) },
                text = { Text(stringResource(R.string.feature_guardian_impl_settings_delete_dialog_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmingDelete = false
                        onDeleteAll()
                    }) { Text(stringResource(R.string.feature_guardian_impl_settings_delete), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.feature_guardian_impl_keep)) } },
            )
        }
    }
}

/** 줄 전체를 눌러도 바뀌는 스위치 (보호자 화면 48dp) */
@Composable
private fun SwitchRow(title: String, body: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun SettingsPreview() {
    PhoneDoctorTheme { GuardianSettingsScreen(
            state = GuardianSettingsUiState(notifyCaution = true, needsReauth = true),
            onNotifyCaution = {}, onRename = {}, onDeleteAll = {}, onReauth = {}, onBack = {},
        ) }
}
