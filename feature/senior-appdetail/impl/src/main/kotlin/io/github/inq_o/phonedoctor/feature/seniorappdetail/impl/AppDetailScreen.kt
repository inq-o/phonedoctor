package io.github.inq_o.phonedoctor.feature.seniorappdetail.impl

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.inq_o.phonedoctor.core.designsystem.component.AppIcon
import io.github.inq_o.phonedoctor.core.designsystem.component.PdOutlinedButton
import io.github.inq_o.phonedoctor.core.designsystem.component.PdPrimaryButton
import io.github.inq_o.phonedoctor.core.designsystem.component.SeniorScreen
import io.github.inq_o.phonedoctor.core.designsystem.component.StatusCircle
import io.github.inq_o.phonedoctor.core.designsystem.component.StatusKind
import io.github.inq_o.phonedoctor.core.designsystem.theme.PhoneDoctorTheme
import io.github.inq_o.phonedoctor.core.model.AlertStatus
import io.github.inq_o.phonedoctor.core.model.AppIdentity
import io.github.inq_o.phonedoctor.core.model.Josa
import io.github.inq_o.phonedoctor.core.model.SymptomAlert
import java.time.LocalDate

@Composable
fun AppDetailScreen(
    alertId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AppDetailViewModel = hiltViewModel<AppDetailViewModel, AppDetailViewModel.Factory>(
        key = "alert-$alertId",
        creationCallback = { it.create(alertId) },
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.onResume()
        onPauseOrDispose {}
    }
    AppDetailScreen(state = state, onUninstall = viewModel::uninstall, onBack = onBack, modifier = modifier)
}

/** S2 앱 상세 · 삭제 유도. 삭제는 시스템 확인창에서 시니어가 직접 확인해야 진행된다 */
@Composable
internal fun AppDetailScreen(
    state: AppDetailUiState,
    onUninstall: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val removed = state is AppDetailUiState.Ready && state.phase == UninstallPhase.REMOVED
    SeniorScreen(
        modifier = modifier,
        actions = {
            when {
                state is AppDetailUiState.Loading -> Unit
                state is AppDetailUiState.Gone || removed ->
                    PdPrimaryButton(stringResource(R.string.feature_senior_appdetail_impl_home), onBack)
                state is AppDetailUiState.Ready -> {
                    PdOutlinedButton(stringResource(R.string.feature_senior_appdetail_impl_later), onBack)
                    PdPrimaryButton(
                        text = stringResource(
                            if (state.phase == UninstallPhase.IDLE || state.phase == UninstallPhase.REQUESTED) {
                                R.string.feature_senior_appdetail_impl_uninstall
                            } else {
                                R.string.feature_senior_appdetail_impl_retry
                            },
                        ),
                        onClick = onUninstall,
                        danger = true,
                        icon = Icons.Filled.Delete,
                    )
                }
            }
        },
    ) {
        Spacer(Modifier.height(8.dp))
        when (state) {
            AppDetailUiState.Loading -> Unit
            AppDetailUiState.Gone -> {
                StatusCircle(StatusKind.OK)
                Headline(stringResource(R.string.feature_senior_appdetail_impl_gone_title))
                Body(stringResource(R.string.feature_senior_appdetail_impl_gone_body), center = true)
            }
            is AppDetailUiState.Ready ->
                if (removed) {
                    StatusCircle(StatusKind.OK)
                    Headline(stringResource(R.string.feature_senior_appdetail_impl_removed_title), announce = true)
                    Body(stringResource(R.string.feature_senior_appdetail_impl_removed_body, Josa.eunNeun(state.alert.label)), center = true)
                } else {
                    AppIcon(state.alert.app.packageName, size = 96.dp)
                    Headline(state.alert.label)
                    when (state.phase) {
                        UninstallPhase.STILL_INSTALLED -> {
                            Notice(
                                stringResource(R.string.feature_senior_appdetail_impl_still_title),
                                stringResource(R.string.feature_senior_appdetail_impl_still_body),
                            )
                        }
                        UninstallPhase.FAILED -> {
                            Notice(
                                stringResource(R.string.feature_senior_appdetail_impl_failed_title),
                                stringResource(R.string.feature_senior_appdetail_impl_failed_body),
                            )
                        }
                        else -> Unit
                    }
                    Text(
                        text = stringResource(R.string.feature_senior_appdetail_impl_reasons_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.fillMaxWidth().semantics { heading() },
                    )
                    Spacer(Modifier.height(8.dp))
                    Body("• " + reasonSentence(state.alert))
                    if (state.alert.days >= 2) {
                        Body("• " + stringResource(R.string.feature_senior_appdetail_impl_reason_days, state.alert.days))
                    }
                    Body(stringResource(R.string.feature_senior_appdetail_impl_reason_explain))
                }
        }
    }
}

@Composable
private fun reasonSentence(alert: SymptomAlert): String {
    val date = runCatching { LocalDate.parse(alert.date) }.getOrNull()
    return if (date == null || date == LocalDate.now()) {
        stringResource(R.string.feature_senior_appdetail_impl_reason_today, alert.count)
    } else {
        stringResource(R.string.feature_senior_appdetail_impl_reason_date, date.monthValue, date.dayOfMonth, alert.count)
    }
}

@Composable
private fun Headline(text: String, announce: Boolean = false) {
    Spacer(Modifier.height(16.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().semantics {
            heading()
            if (announce) liveRegion = LiveRegionMode.Polite
        },
    )
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun Body(text: String, center: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
}

/** 삭제가 끝나지 않았을 때의 안내. 화면 낭독이 바뀐 내용을 읽어 준다 */
@Composable
private fun Notice(title: String, body: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    )
    Spacer(Modifier.height(4.dp))
    Body(body)
    Spacer(Modifier.height(8.dp))
}

private val PreviewAlert = SymptomAlert(
    1, AppIdentity("com.example.cleaner", "00", 1), "초고속 클리너", AlertStatus.OPEN, null,
    LocalDate.now().toString(), 5, 2, 0, 0, "preview", 0, 0,
)

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ReadyPreview() {
    PhoneDoctorTheme { AppDetailScreen(AppDetailUiState.Ready(PreviewAlert, UninstallPhase.IDLE), {}, {}) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720, fontScale = 2f)
@Composable
private fun StillInstalledLargeFontPreview() {
    PhoneDoctorTheme { AppDetailScreen(AppDetailUiState.Ready(PreviewAlert, UninstallPhase.STILL_INSTALLED), {}, {}) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun RemovedPreview() {
    PhoneDoctorTheme { AppDetailScreen(AppDetailUiState.Ready(PreviewAlert, UninstallPhase.REMOVED), {}, {}) }
}
