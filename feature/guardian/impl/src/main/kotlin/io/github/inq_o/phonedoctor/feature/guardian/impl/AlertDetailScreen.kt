package io.github.inq_o.phonedoctor.feature.guardian.impl

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.inq_o.phonedoctor.core.data.MESSAGE_TO_SENIOR_MAX
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianOutlinedButton
import io.github.inq_o.phonedoctor.core.designsystem.component.PdButtonPair
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianPrimaryButton
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianScreen
import io.github.inq_o.phonedoctor.core.designsystem.theme.PhoneDoctorTheme
import io.github.inq_o.phonedoctor.core.model.FamilyAlert
import io.github.inq_o.phonedoctor.core.model.FamilyAlertStatus
import io.github.inq_o.phonedoctor.core.model.FamilyDecision
import io.github.inq_o.phonedoctor.core.model.FamilySymptom
import io.github.inq_o.phonedoctor.core.model.FamilyVerdict
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun AlertDetailScreen(
    alertId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AlertDetailViewModel = hiltViewModel<AlertDetailViewModel, AlertDetailViewModel.Factory>(
        key = "request-$alertId",
        creationCallback = { it.create(alertId) },
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.onShown()
        onPauseOrDispose {}
    }
    AlertDetailScreen(
        state = state,
        onMessageChange = viewModel::onMessageChange,
        onDecide = viewModel::decide,
        onRetry = viewModel::load,
        onBack = onBack,
        modifier = modifier,
    )
}

/** G2 요청 상세·응답. 오른쪽(아래)이 주요 행동 [지우자고 하기] */
@Composable
internal fun AlertDetailScreen(
    state: AlertDetailUiState,
    onMessageChange: (String) -> Unit,
    onDecide: (FamilyDecision) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = state as? AlertDetailUiState.Ready
    val answerable = ready != null && ready.canAnswer
    GuardianScreen(
        title = ready?.let { stringResource(R.string.feature_guardian_impl_home_phone, seniorName(it.alert.seniorName)) },
        modifier = modifier,
        actions = {
            when {
                // 버튼 쌍 (ux-flows): 왼쪽 물러나기, 오른쪽 주요 행동
                state is AlertDetailUiState.Failed -> PdButtonPair(
                    first = { GuardianOutlinedButton(stringResource(R.string.feature_guardian_impl_back), onBack, it) },
                    second = { GuardianPrimaryButton(stringResource(R.string.feature_guardian_impl_retry), onRetry, it) },
                    minHeight = 48.dp,
                    gap = 8.dp,
                )
                answerable -> {
                    val sending = ready!!.sending != null
                    // 알림 전용(이미 허용한 앱)에는 [괜찮은 앱이에요]가 없다
                    val uninstall: @Composable (Modifier) -> Unit = {
                        GuardianPrimaryButton(
                            stringResource(R.string.feature_guardian_impl_decide_uninstall),
                            { onDecide(FamilyDecision.RECOMMEND_UNINSTALL) },
                            it,
                            enabled = !sending,
                            danger = true,
                        )
                    }
                    if (ready.alert.notice) {
                        uninstall(Modifier)
                    } else {
                        PdButtonPair(
                            first = { GuardianOutlinedButton(stringResource(R.string.feature_guardian_impl_decide_allow), { onDecide(FamilyDecision.ALLOW) }, it, enabled = !sending) },
                            second = uninstall,
                            minHeight = 48.dp,
                            gap = 8.dp,
                        )
                    }
                }
                else -> GuardianOutlinedButton(stringResource(R.string.feature_guardian_impl_back), onBack)
            }
        },
    ) {
        when (state) {
            AlertDetailUiState.Loading -> CircularProgressIndicator()
            is AlertDetailUiState.Failed -> {
                Headline(stringResource(R.string.feature_guardian_impl_detail_load_failed), announce = true)
                Body(state.error.message())
            }
            AlertDetailUiState.NotFound -> {
                Headline(stringResource(R.string.feature_guardian_impl_detail_not_found), announce = true)
                Body(stringResource(R.string.feature_guardian_impl_detail_not_found_body))
            }
            is AlertDetailUiState.Ready -> Detail(state, answerable, onMessageChange)
        }
    }
}

@Composable
private fun Detail(state: AlertDetailUiState.Ready, answerable: Boolean, onMessageChange: (String) -> Unit) {
    val alert = state.alert
    Text(alert.appLabel, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth().semantics { heading() })
    Spacer(Modifier.height(4.dp))
    Text(
        listOfNotNull(alert.packageName, alert.versionName?.let { "v$it" }).joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
    )
    alert.firstInstallTime?.let {
        Text(stringResource(R.string.feature_guardian_impl_detail_installed, formatDateTime(it)), style = MaterialTheme.typography.bodyMedium)
    }
    installSourceText(alert.installSource)?.let {
        Text(stringResource(R.string.feature_guardian_impl_detail_source, it), style = MaterialTheme.typography.bodyMedium)
    }
    Spacer(Modifier.height(16.dp))

    // 지금 상태: 방금 답함 / 먼저 온 답 / 거절됨 / 결과 / 만료 / 알림 전용 안내
    when {
        state.answeredFirst != null -> ErrorText(
            stringResource(R.string.feature_guardian_impl_answered_first, state.answeredFirst.guardianName.trim().ifEmpty { stringResource(R.string.feature_guardian_impl_guardian_fallback) }),
        )
        state.answered -> Headline(
            if (alert.answer?.decision == FamilyDecision.ALLOW) {
                stringResource(R.string.feature_guardian_impl_answer_sent_allow)
            } else {
                stringResource(R.string.feature_guardian_impl_answer_sent, seniorName(alert.seniorName))
            },
            announce = true,
        )
        state.rejected && alert.answerable -> ErrorText(stringResource(R.string.feature_guardian_impl_answer_rejected))
        !alert.answerable || alert.status == FamilyAlertStatus.EXPIRED -> Headline(statusLine(alert))
        alert.notice -> Body(stringResource(R.string.feature_guardian_impl_detail_notice))
    }
    state.error?.let { ErrorText(it.message()) }

    // 둘 다 있으면 감지 근거 위·판정 근거 아래 (ux-flows G2)
    alert.symptom?.let { Evidence(it) }
    alert.verdict?.let { Verdict(it) }
    if (alert.symptom == null && alert.verdict == null) Body(stringResource(R.string.feature_guardian_impl_alert_install_scan))
    alert.answer?.message?.takeIf { it.isNotBlank() }?.let {
        Body(stringResource(R.string.feature_guardian_impl_detail_message_sent, it))
    }

    if (answerable) {
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = state.message,
            onValueChange = onMessageChange,
            label = { Text(stringResource(R.string.feature_guardian_impl_detail_message_label, seniorName(alert.seniorName))) },
            placeholder = { Text(stringResource(R.string.feature_guardian_impl_detail_message_hint)) },
            supportingText = { Text("${state.message.length}/$MESSAGE_TO_SENIOR_MAX") },
            singleLine = true,
            enabled = state.sending == null,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Body(stringResource(R.string.feature_guardian_impl_detail_explain, seniorName(alert.seniorName)))
        if (state.sending != null) CircularProgressIndicator()
    }
}

/** 감지 근거 (ux-flows G2) */
@Composable
private fun Evidence(symptom: FamilySymptom) {
    Text(
        stringResource(R.string.feature_guardian_impl_detail_evidence_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth().semantics { heading() },
    )
    Spacer(Modifier.height(8.dp))
    Body(evidenceLine(symptom) + " " + stringResource(R.string.feature_guardian_impl_detail_outside))
    Body(stringResource(R.string.feature_guardian_impl_detail_first_last, formatTime(symptom.firstAt), formatTime(symptom.lastAt)))
    if (symptom.days >= 2) Body(stringResource(R.string.feature_guardian_impl_detail_days, symptom.days))
    Text(
        stringResource(R.string.feature_guardian_impl_detail_rule, symptom.detectorVersion),
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(16.dp))
}

/** 판정 근거 (설치 시점 분류기, classifier.md §4 보호자 문구) */
@Composable
private fun Verdict(verdict: FamilyVerdict) {
    Text(
        stringResource(R.string.feature_guardian_impl_detail_verdict_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth().semantics { heading() },
    )
    Spacer(Modifier.height(8.dp))
    verdict.reasons.forEachIndexed { i, code -> Body("${i + 1}. ${reasonText(code)}") }
    Text(
        stringResource(R.string.feature_guardian_impl_detail_verdict_meta, levelText(verdict.level), "%.2f".format(verdict.score), verdict.modelVersion),
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun levelText(level: String): String = when (level) {
    "RISK" -> stringResource(R.string.feature_guardian_impl_level_risk)
    "CAUTION" -> stringResource(R.string.feature_guardian_impl_level_caution)
    else -> level
}

/** 앱이 모르는 코드(서버·모델이 먼저 더함)는 코드 그대로 */
@Composable
private fun reasonText(code: String): String = when (code) {
    "AD_SDK_HEAVY" -> stringResource(R.string.feature_guardian_impl_reason_ad_sdk)
    "OVERLAY_PERMISSION" -> stringResource(R.string.feature_guardian_impl_reason_overlay)
    "LAUNCHER_ICON_HIDDEN" -> stringResource(R.string.feature_guardian_impl_reason_hidden)
    "SIDELOADED" -> stringResource(R.string.feature_guardian_impl_reason_sideloaded)
    "DEVICE_ADMIN" -> stringResource(R.string.feature_guardian_impl_reason_device_admin)
    "ACCESSIBILITY_SERVICE" -> stringResource(R.string.feature_guardian_impl_reason_accessibility)
    "NOTIFICATION_LISTENER" -> stringResource(R.string.feature_guardian_impl_reason_notification_listener)
    "SMS_CALL_ACCESS" -> stringResource(R.string.feature_guardian_impl_reason_sms_call)
    "INSTALL_OTHER_APPS" -> stringResource(R.string.feature_guardian_impl_reason_install_other)
    "BOOT_AUTOSTART" -> stringResource(R.string.feature_guardian_impl_reason_boot)
    "IMPERSONATION" -> stringResource(R.string.feature_guardian_impl_reason_impersonation)
    "FREQUENT_BACKGROUND_LAUNCH" -> stringResource(R.string.feature_guardian_impl_reason_background)
    "CLEANER_BOOSTER_PATTERN" -> stringResource(R.string.feature_guardian_impl_reason_cleaner)
    else -> code
}

@Composable
private fun installSourceText(source: String?): String? = when (source) {
    "GOOGLE_PLAY" -> stringResource(R.string.feature_guardian_impl_source_play)
    "ONE_STORE" -> stringResource(R.string.feature_guardian_impl_source_onestore)
    "GALAXY_STORE" -> stringResource(R.string.feature_guardian_impl_source_galaxy)
    "BROWSER_OR_FILE" -> stringResource(R.string.feature_guardian_impl_source_file)
    "ADB" -> stringResource(R.string.feature_guardian_impl_source_adb)
    "OTHER_STORE" -> stringResource(R.string.feature_guardian_impl_source_other)
    else -> null
}

private fun formatTime(at: Instant): String =
    at.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN))

private fun formatDateTime(at: Instant): String =
    at.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M월 d일 a h:mm", Locale.KOREAN))

private val PreviewAlert = FamilyAlert(
    alertId = "a1", seniorName = "엄마", status = FamilyAlertStatus.OPEN, notice = false, fromInstallScan = false,
    appLabel = "초고속 클리너", packageName = "com.fast.cleaner", versionName = "3.2.1", installSource = "BROWSER_OR_FILE",
    firstInstallTime = Instant.parse("2026-10-03T05:14:00Z"),
    symptom = FamilySymptom(LocalDate.now().toString(), 12, 3, Instant.now(), Instant.now(), "usage-v1"),
    answer = null, outcome = null, createdAt = Instant.now(), updatedAt = Instant.now(),
)

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun DetailPreview() {
    PhoneDoctorTheme { AlertDetailScreen(AlertDetailUiState.Ready(PreviewAlert, message = "엄마 이거 광고 앱이에요"), {}, {}, {}, {}) }
}
