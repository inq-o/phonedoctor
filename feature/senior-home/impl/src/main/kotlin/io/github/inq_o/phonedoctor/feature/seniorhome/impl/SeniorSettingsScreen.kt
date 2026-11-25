package io.github.inq_o.phonedoctor.feature.seniorhome.impl

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.inq_o.phonedoctor.core.data.DISPLAY_NAME_MAX
import io.github.inq_o.phonedoctor.core.designsystem.component.PdButtonPair
import io.github.inq_o.phonedoctor.core.designsystem.component.PdOutlinedButton
import io.github.inq_o.phonedoctor.core.designsystem.component.PdPrimaryButton
import io.github.inq_o.phonedoctor.core.designsystem.component.SeniorScreen
import io.github.inq_o.phonedoctor.core.designsystem.theme.PhoneDoctorTheme

@Composable
fun SeniorSettingsScreen(
    onFamilyClick: () -> Unit,
    onSitesClick: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SeniorSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 지우는 도중 뒤로 가면 화면과 함께 작업이 취소돼 반쯤 지운 채 남는다
    BackHandler(enabled = state.working) {}
    SeniorSettingsScreen(
        state = state,
        onRename = viewModel::rename,
        onFamilyClick = onFamilyClick,
        onSitesClick = onSitesClick,
        onOpenTextSize = { context.openTextSize() },
        onDeleteAll = viewModel::deleteAll,
        onBack = onBack,
        modifier = modifier,
        onBlockUnknownSources = viewModel::setUnknownSourcesBlocked,
        onUnsuspend = viewModel::unsuspend,
    )
    LifecycleResumeEffect(Unit) {
        viewModel.refreshStrongMode()
        onPauseOrDispose {}
    }
}

/** 글자 크기는 폰 설정을 그대로 따른다 (ux-flows 원칙 7). 폰마다 화면이 달라 디스플레이 설정을 연다 */
private fun Context.openTextSize() {
    try {
        startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS))
    } catch (e: ActivityNotFoundException) {
        startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

/** S7 설정. 지우기는 확인창을 거친다 */
@Composable
internal fun SeniorSettingsScreen(
    state: SeniorSettingsUiState,
    onRename: (String) -> Unit,
    onFamilyClick: () -> Unit,
    onSitesClick: () -> Unit,
    onOpenTextSize: () -> Unit,
    onDeleteAll: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onBlockUnknownSources: (Boolean) -> Unit = {},
    onUnsuspend: (String) -> Unit = {},
) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    SeniorScreen(
        modifier = modifier,
        topBar = {
            Text(
                stringResource(R.string.feature_senior_home_impl_settings),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp).semantics { heading() },
            )
        },
        actions = { PdPrimaryButton(stringResource(R.string.feature_senior_home_impl_settings_home), onBack) },
    ) {
        state.result?.let { ResultText(it) }
        if (state.working) CircularProgressIndicator()
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.familyAvailable) {
                Section(stringResource(R.string.feature_senior_home_impl_settings_name_title)) {
                    Body(
                        stringResource(
                            R.string.feature_senior_home_impl_settings_name_body,
                            state.displayName ?: stringResource(R.string.feature_senior_home_impl_settings_name_default),
                        ),
                    )
                    PdOutlinedButton(stringResource(R.string.feature_senior_home_impl_settings_name_change), { renaming = true }, enabled = !state.working)
                }
                Section(stringResource(R.string.feature_senior_home_impl_settings_family_title)) {
                    PdOutlinedButton(stringResource(R.string.feature_senior_home_impl_settings_family_open), onFamilyClick)
                }
            }
            if (state.siteAlertsAvailable) {
                Section(stringResource(R.string.feature_senior_home_impl_settings_sites_title)) {
                    Body(stringResource(R.string.feature_senior_home_impl_settings_sites_body))
                    PdOutlinedButton(stringResource(R.string.feature_senior_home_impl_settings_sites_open), onSitesClick)
                }
            }
            state.strongMode?.let { strong ->
                Section(stringResource(R.string.feature_senior_home_impl_settings_strong_title)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .toggleable(value = strong.unknownSourcesBlocked, role = Role.Switch, onValueChange = onBlockUnknownSources),
                    ) {
                        Text(
                            stringResource(R.string.feature_senior_home_impl_settings_strong_unknown),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(16.dp))
                        Switch(checked = strong.unknownSourcesBlocked, onCheckedChange = null)
                    }
                    if (strong.suspended.isEmpty()) {
                        Body(stringResource(R.string.feature_senior_home_impl_settings_strong_none))
                    } else {
                        Body(stringResource(R.string.feature_senior_home_impl_settings_strong_suspended))
                        strong.suspended.forEach { app ->
                            PdOutlinedButton(stringResource(R.string.feature_senior_home_impl_settings_strong_unsuspend, app.label), { onUnsuspend(app.packageName) })
                        }
                    }
                }
            }
            Section(stringResource(R.string.feature_senior_home_impl_settings_text_title)) {
                Body(stringResource(R.string.feature_senior_home_impl_settings_text_sample))
                PdOutlinedButton(stringResource(R.string.feature_senior_home_impl_settings_text_open), onOpenTextSize)
            }
            if (state.familyAvailable) {
                Section(stringResource(R.string.feature_senior_home_impl_settings_delete_title)) {
                    Body(stringResource(R.string.feature_senior_home_impl_settings_delete_body))
                    PdOutlinedButton(stringResource(R.string.feature_senior_home_impl_settings_delete), { confirmingDelete = true }, enabled = !state.working)
                }
            }
        }
    }
    if (renaming) {
        RenameDialog(
            current = state.displayName.orEmpty(),
            onSave = {
                renaming = false
                onRename(it)
            },
            onCancel = { renaming = false },
        )
    }
    if (confirmingDelete) {
        DeleteDialog(
            onDelete = {
                confirmingDelete = false
                onDeleteAll()
            },
            onKeep = { confirmingDelete = false },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth().semantics { heading() })
        content()
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
    }
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ResultText(result: SettingsResult) {
    val ok = result == SettingsResult.NAME_SAVED || result == SettingsResult.DELETED
    Text(
        stringResource(
            when (result) {
                SettingsResult.NAME_SAVED -> R.string.feature_senior_home_impl_settings_saved
                SettingsResult.DELETED -> R.string.feature_senior_home_impl_settings_deleted
                SettingsResult.NETWORK -> R.string.feature_senior_home_impl_settings_network
                SettingsResult.APP_CHECK -> R.string.feature_senior_home_impl_settings_app_check
                SettingsResult.BUSY -> R.string.feature_senior_home_impl_settings_busy
                SettingsResult.FAILED -> R.string.feature_senior_home_impl_settings_failed
            },
        ),
        style = MaterialTheme.typography.titleLarge,
        color = if (ok) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    )
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun RenameDialog(current: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(current) }
    val valid = name.trim().length in 1..DISPLAY_NAME_MAX
    Dialog(onDismissRequest = onCancel) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    stringResource(R.string.feature_senior_home_impl_settings_name_dialog),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(DISPLAY_NAME_MAX) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    label = { Text(stringResource(R.string.feature_senior_home_impl_settings_name_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                PdOutlinedButton(stringResource(R.string.feature_senior_home_impl_settings_keep), onCancel)
                PdPrimaryButton(stringResource(R.string.feature_senior_home_impl_settings_name_save), { onSave(name) }, enabled = valid)
            }
        }
    }
}

/** 바깥을 누르거나 뒤로 가면 그대로 둔다 (지우는 건 빨간 버튼으로만) */
@Composable
private fun DeleteDialog(onDelete: () -> Unit, onKeep: () -> Unit) {
    Dialog(onDismissRequest = onKeep) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    stringResource(R.string.feature_senior_home_impl_settings_delete_dialog_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(R.string.feature_senior_home_impl_settings_delete_dialog_body), style = MaterialTheme.typography.bodyLarge)
                PdButtonPair(
                    first = { PdOutlinedButton(stringResource(R.string.feature_senior_home_impl_settings_keep), onKeep, it) },
                    second = { PdPrimaryButton(stringResource(R.string.feature_senior_home_impl_settings_delete), onDelete, it, danger = true) },
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun SettingsPreview() {
    PhoneDoctorTheme { SeniorSettingsScreen(SeniorSettingsUiState(familyAvailable = true, displayName = "엄마", siteAlertsAvailable = true), {}, {}, {}, {}, {}, {}) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800, fontScale = 2f)
@Composable
private fun SettingsReleaseLargeFontPreview() {
    PhoneDoctorTheme { SeniorSettingsScreen(SeniorSettingsUiState(familyAvailable = false), {}, {}, {}, {}, {}, {}) }
}
