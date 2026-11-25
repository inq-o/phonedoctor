package io.github.inq_o.phonedoctor.feature.guardian.impl

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.inq_o.phonedoctor.core.data.DISPLAY_NAME_MAX
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianBand
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianPrimaryButton
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianRow
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianRowDivider
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianScreen
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianSectionHeader
import io.github.inq_o.phonedoctor.core.designsystem.theme.GuardianTheme
import io.github.inq_o.phonedoctor.core.designsystem.theme.PhoneDoctorTheme
import io.github.inq_o.phonedoctor.core.model.FamilyLink
import io.github.inq_o.phonedoctor.core.model.LinkStatus
import java.time.ZoneId
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
    LifecycleResumeEffect(Unit) {
        viewModel.loadLinks()
        onPauseOrDispose {}
    }
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
        onPhoneName = viewModel::setPhoneName,
        onRevoke = viewModel::revoke,
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

/** G4 설정 (ux-flows §4 G4): 연결된 폰(표시 이름·연결 끊기), 알림, 계정. 묶음 목록, 지우는 항목은 빨간 글자 + 확인창 */
@Composable
internal fun GuardianSettingsScreen(
    state: GuardianSettingsUiState,
    onNotifyCaution: (Boolean) -> Unit,
    onRename: (String) -> Unit,
    onDeleteAll: () -> Unit,
    onReauth: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onFalsePositiveFeedback: (Boolean) -> Unit = {},
    onPhoneName: (String, String?) -> Unit = { _, _ -> },
    onRevoke: (FamilyLink) -> Unit = {},
) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    var managing by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmingRevoke by rememberSaveable { mutableStateOf<String?>(null) }
    GuardianScreen(
        title = stringResource(R.string.feature_guardian_impl_settings_title),
        onBack = onBack.takeIf { !state.working },
        backLabel = stringResource(R.string.feature_guardian_impl_back),
        contentPadding = 0.dp,
        modifier = modifier,
        actions = {
            if (state.needsReauth) GuardianPrimaryButton(stringResource(R.string.feature_guardian_impl_settings_reauth), onReauth, enabled = !state.working)
        },
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            state.error?.let { ErrorText(it.message()) }
            if (state.needsReauth) ErrorText(stringResource(R.string.feature_guardian_impl_settings_reauth_body))
            if (state.wrongAccount) ErrorText(stringResource(R.string.feature_guardian_impl_settings_reauth_wrong_account))
            if (state.saved) Body(stringResource(R.string.feature_guardian_impl_settings_saved))
            state.linkMessage?.let { m ->
                val name = phoneName(m.linkId, m.seniorName)
                if (m.error == null) {
                    Body(stringResource(if (m.wasPending) R.string.feature_guardian_impl_home_cancelled else R.string.feature_guardian_impl_home_revoked, name))
                } else {
                    ErrorText(stringResource(R.string.feature_guardian_impl_home_revoke_failed))
                    Body(m.error.message())
                }
            }
            if (state.working || state.revoking != null) CircularProgressIndicator()
        }

        GuardianSectionHeader(stringResource(R.string.feature_guardian_impl_settings_links))
        GuardianBand {
            val links = state.links
            when {
                links == null && state.linksError != null -> GuardianRow(state.linksError.message())
                links == null -> GuardianRow(stringResource(R.string.feature_guardian_impl_settings_links_loading))
                links.isEmpty() -> GuardianRow(stringResource(R.string.feature_guardian_impl_home_empty_title))
                else -> links.forEachIndexed { i, link ->
                    if (i > 0) GuardianRowDivider()
                    val pending = link.status == LinkStatus.PENDING
                    GuardianRow(
                        title = if (pending) phoneName(null, link.seniorName, unnamed = R.string.feature_guardian_impl_phone_other) else phoneName(link.linkId, link.seniorName),
                        subtitle = if (pending) {
                            stringResource(R.string.feature_guardian_impl_home_status_pending)
                        } else {
                            linkedSince(link)
                        },
                        onClick = { managing = link.linkId }.takeIf { state.revoking == null },
                    )
                }
            }
        }

        GuardianSectionHeader(stringResource(R.string.feature_guardian_impl_settings_notifications), topPadding = 24.dp)
        GuardianBand {
            SwitchRow(
                title = stringResource(R.string.feature_guardian_impl_settings_caution),
                body = stringResource(R.string.feature_guardian_impl_settings_caution_body),
                checked = state.notifyCaution,
                enabled = !state.working,
                onChange = onNotifyCaution,
            )
            GuardianRowDivider()
            SwitchRow(
                title = stringResource(R.string.feature_guardian_impl_settings_feedback),
                body = stringResource(R.string.feature_guardian_impl_settings_feedback_body),
                checked = state.falsePositiveFeedback,
                enabled = true,
                onChange = onFalsePositiveFeedback,
            )
        }

        GuardianSectionHeader(stringResource(R.string.feature_guardian_impl_settings_account), topPadding = 24.dp)
        GuardianBand {
            GuardianRow(
                title = stringResource(R.string.feature_guardian_impl_settings_name),
                subtitle = state.displayName ?: stringResource(R.string.feature_guardian_impl_settings_name_default),
                onClick = { renaming = true }.takeIf { !state.working },
            )
            GuardianRowDivider()
            GuardianRow(
                title = stringResource(R.string.feature_guardian_impl_settings_delete_title),
                subtitle = stringResource(R.string.feature_guardian_impl_settings_delete_body),
                titleColor = MaterialTheme.colorScheme.error,
                emphasized = true,
                onClick = { confirmingDelete = true }.takeIf { !state.working },
            )
        }
        Spacer(Modifier.height(16.dp))
    }

    val managed = state.links?.firstOrNull { it.linkId == managing }
    if (managed != null) {
        ManageLinkDialog(
            link = managed,
            onSaveName = { name ->
                managing = null
                onPhoneName(managed.linkId, name)
            },
            onRevoke = {
                managing = null
                confirmingRevoke = managed.linkId
            },
            onDismiss = { managing = null },
        )
    }
    val revokeTarget = state.links?.firstOrNull { it.linkId == confirmingRevoke }
    if (revokeTarget != null) {
        val pending = revokeTarget.status == LinkStatus.PENDING
        val name = if (pending) phoneName(null, revokeTarget.seniorName, unnamed = R.string.feature_guardian_impl_phone_other) else phoneName(revokeTarget.linkId, revokeTarget.seniorName)
        GuardianTheme {
            AlertDialog(
                onDismissRequest = { confirmingRevoke = null },
                title = { Text(stringResource(if (pending) R.string.feature_guardian_impl_cancel_dialog_title else R.string.feature_guardian_impl_revoke_dialog_title, name)) },
                text = { Text(stringResource(if (pending) R.string.feature_guardian_impl_cancel_dialog_body else R.string.feature_guardian_impl_revoke_dialog_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmingRevoke = null
                        onRevoke(revokeTarget)
                    }) {
                        Text(
                            stringResource(if (pending) R.string.feature_guardian_impl_home_cancel_request else R.string.feature_guardian_impl_home_revoke),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = { TextButton(onClick = { confirmingRevoke = null }) { Text(stringResource(R.string.feature_guardian_impl_cancel)) } },
            )
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
                dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.feature_guardian_impl_cancel)) } },
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
                dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.feature_guardian_impl_cancel)) } },
            )
        }
    }
}

/** 연결된 폰 하나 관리: 표시 이름(연결됨만) + 연결 끊기·요청 취소 */
@Composable
private fun ManageLinkDialog(link: FamilyLink, onSaveName: (String?) -> Unit, onRevoke: () -> Unit, onDismiss: () -> Unit) {
    val pending = link.status == LinkStatus.PENDING
    val current = LocalChosenPhoneNames.current[link.linkId]
    var choice by rememberSaveable(link.linkId, stateSaver = PhoneNameChoiceSaver) {
        mutableStateOf(current?.let { PhoneNameChoice(custom = true, text = it) } ?: PhoneNameChoice())
    }
    GuardianTheme {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(if (pending) phoneName(null, link.seniorName, unnamed = R.string.feature_guardian_impl_phone_other) else phoneName(link.linkId, link.seniorName))
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (!pending) PhoneNamePicker(choice, onChange = { choice = it })
                    TextButton(onClick = onRevoke, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(
                            stringResource(if (pending) R.string.feature_guardian_impl_home_cancel_request else R.string.feature_guardian_impl_home_revoke),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            },
            confirmButton = {
                if (!pending) {
                    // 비우면 고른 이름을 지운다 (상대가 정한 이름으로 돌아감)
                    TextButton(onClick = { onSaveName(choice.value) }) { Text(stringResource(R.string.feature_guardian_impl_phone_name_save)) }
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_guardian_impl_cancel)) } },
        )
    }
}

/** "김영희님, 11월 25일 연결" / "11월 25일 연결" */
@Composable
private fun linkedSince(link: FamilyLink): String {
    val day = (link.activatedAt ?: link.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
    val date = stringResource(R.string.feature_guardian_impl_history_day, day.monthValue, day.dayOfMonth)
    val own = link.seniorName.trim()
    return if (own.isEmpty()) {
        stringResource(R.string.feature_guardian_impl_settings_linked_on, date)
    } else {
        stringResource(R.string.feature_guardian_impl_settings_linked_by, own, date)
    }
}

/** 줄 전체를 눌러도 바뀌는 스위치 (흰 띠 안, 72dp) */
@Composable
private fun SwitchRow(title: String, body: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
