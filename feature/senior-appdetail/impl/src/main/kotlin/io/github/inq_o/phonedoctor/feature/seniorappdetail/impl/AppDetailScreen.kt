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
import io.github.inq_o.phonedoctor.core.designsystem.component.PdButtonPair
import io.github.inq_o.phonedoctor.core.designsystem.component.PdOutlinedButton
import io.github.inq_o.phonedoctor.core.designsystem.component.PdPrimaryButton
import io.github.inq_o.phonedoctor.core.designsystem.component.SeniorScreen
import io.github.inq_o.phonedoctor.core.designsystem.component.StatusCircle
import io.github.inq_o.phonedoctor.core.designsystem.component.StatusKind
import io.github.inq_o.phonedoctor.core.designsystem.theme.PhoneDoctorTheme
import io.github.inq_o.phonedoctor.core.model.AlertFamilyState
import io.github.inq_o.phonedoctor.core.model.AlertStatus
import io.github.inq_o.phonedoctor.core.model.AppIdentity
import io.github.inq_o.phonedoctor.core.model.FamilyDecision
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
                state is AppDetailUiState.Gone || state is AppDetailUiState.Allowed || removed ->
                    PdPrimaryButton(stringResource(R.string.feature_senior_appdetail_impl_home), onBack)
                // 가족 답을 기다리는 동안은 빨간 버튼 대신 테두리 버튼으로 (지우라고 재촉하지 않되, 본인이 정할 수 있게)
                state is AppDetailUiState.Ready && state.waitingForFamily -> PdButtonPair(
                    first = { PdOutlinedButton(stringResource(R.string.feature_senior_appdetail_impl_later), onBack, it) },
                    second = { PdOutlinedButton(stringResource(R.string.feature_senior_appdetail_impl_uninstall_now), onUninstall, it) },
                )
                // 버튼 쌍: 왼쪽 물러나기, 오른쪽 지우기. 바로 뒤에 뜨는 시스템 삭제 창 [취소 | 확인]과 같은 순서
                state is AppDetailUiState.Ready -> PdButtonPair(
                    first = { PdOutlinedButton(stringResource(R.string.feature_senior_appdetail_impl_later), onBack, it) },
                    second = {
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
                            modifier = it,
                        )
                    },
                )
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
            is AppDetailUiState.Allowed -> {
                StatusCircle(StatusKind.OK)
                Headline(stringResource(R.string.feature_senior_appdetail_impl_allowed_title))
                Body(
                    stringResource(R.string.feature_senior_appdetail_impl_allowed_body, guardian(state.guardianName)),
                    center = true,
                )
                state.message?.takeIf { it.isNotBlank() }?.let { Body("“$it”", center = true) }
            }
            is AppDetailUiState.Ready ->
                if (removed) {
                    StatusCircle(StatusKind.OK)
                    Headline(stringResource(R.string.feature_senior_appdetail_impl_removed_title), announce = true)
                    Body(stringResource(R.string.feature_senior_appdetail_impl_removed_body, Josa.eunNeun(state.alert.label)), center = true)
                } else {
                    AppIcon(state.alert.app.packageName, size = 96.dp)
                    Headline(state.alert.label)
                    val family = state.alert.family
                    if (state.familyRecommends && family != null) {
                        // 보호자가 지우자고 함 + 한마디 (ux-flows S2 "💬 민지(딸)")
                        Notice(
                            stringResource(R.string.feature_senior_appdetail_impl_family_recommends, guardian(family.guardianName)),
                            family.messageToSenior?.takeIf { it.isNotBlank() }?.let { "“$it”" }.orEmpty(),
                        )
                    } else if (state.waitingForFamily) {
                        Notice(
                            stringResource(R.string.feature_senior_appdetail_impl_family_waiting_title),
                            stringResource(R.string.feature_senior_appdetail_impl_family_waiting_body),
                            emphasis = false,
                        )
                    }
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
                    // 근거가 하나면 글머리표 없이 문장으로 (ux-flows S2)
                    if (state.alert.days >= 2) {
                        Body("• " + reasonSentence(state.alert))
                        Body("• " + stringResource(R.string.feature_senior_appdetail_impl_reason_days, state.alert.days))
                    } else {
                        Body(reasonSentence(state.alert))
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

/**
 * 눈에 띄게 알릴 문장 (삭제가 안 끝남, 가족 소식). 화면 낭독이 바뀐 내용을 읽어 준다.
 * [emphasis]가 false면 빨간 글자를 쓰지 않는다 (기다리는 중은 경고가 아님)
 */
@Composable
private fun Notice(title: String, body: String, emphasis: Boolean = true) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = if (emphasis) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    )
    Spacer(Modifier.height(4.dp))
    if (body.isNotEmpty()) Body(body)
    Spacer(Modifier.height(8.dp))
}

/** 보호자 이름. 비었으면 "가족" */
@Composable
private fun guardian(name: String?): String =
    name?.trim()?.ifEmpty { null } ?: stringResource(R.string.feature_senior_appdetail_impl_family_fallback)

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
private fun FamilyRecommendsPreview() {
    val alert = PreviewAlert.copy(family = AlertFamilyState(FamilyDecision.RECOMMEND_UNINSTALL, "민지", "엄마 이거 광고 앱이에요", closed = false))
    PhoneDoctorTheme { AppDetailScreen(AppDetailUiState.Ready(alert, UninstallPhase.IDLE), {}, {}) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720, fontScale = 2f)
@Composable
private fun FamilyWaitingLargeFontPreview() {
    val alert = PreviewAlert.copy(family = AlertFamilyState(null, null, null, closed = false))
    PhoneDoctorTheme { AppDetailScreen(AppDetailUiState.Ready(alert, UninstallPhase.IDLE), {}, {}) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun RemovedPreview() {
    PhoneDoctorTheme { AppDetailScreen(AppDetailUiState.Ready(PreviewAlert, UninstallPhase.REMOVED), {}, {}) }
}
