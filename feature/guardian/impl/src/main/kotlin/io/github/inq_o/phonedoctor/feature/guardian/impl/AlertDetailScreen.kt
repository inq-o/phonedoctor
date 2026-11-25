package io.github.inq_o.phonedoctor.feature.guardian.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.inq_o.phonedoctor.core.data.MESSAGE_TO_SENIOR_MAX
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianBand
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianOutlinedButton
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianPrimaryButton
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianRow
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianRowDivider
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianScreen
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianSectionHeader
import io.github.inq_o.phonedoctor.core.designsystem.component.PdButtonPair
import io.github.inq_o.phonedoctor.core.designsystem.theme.LocalBadgeColors
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

/**
 * G2 요청 상세·응답 (ux-flows §4 G2): 앱과 배지 → 감지 내용·판정 근거 표 → "광고 판단은 틀릴 수 있어요" → 자세히 보기(접힘) → 진행.
 * 답할 수 있으면 본문 끝에 같이 보낼 메시지, 아래에 [허용 | 삭제 요청] (오른쪽이 주요 행동, 파랑)
 */
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
        title = ready?.let { phoneName(it.alert.linkIds, it.alert.seniorName) },
        onBack = onBack,
        backLabel = stringResource(R.string.feature_guardian_impl_back),
        contentPadding = 0.dp,
        modifier = modifier,
        // 아래에 붙는 것은 오류와 버튼만: 메시지 입력칸·안내는 본문과 같이 스크롤 (글꼴 200%·키보드에서도 버튼이 잘리지 않게, §7)
        bottomBar = if (answerable) ({ AnswerBar(ready!!, onDecide) }) else null,
        actions = if (state is AlertDetailUiState.Failed) ({ GuardianPrimaryButton(stringResource(R.string.feature_guardian_impl_retry), onRetry) }) else null,
    ) {
        when (state) {
            AlertDetailUiState.Loading -> CircularProgressIndicator(Modifier.padding(16.dp))
            is AlertDetailUiState.Failed -> Column(Modifier.padding(horizontal = 16.dp)) {
                Headline(stringResource(R.string.feature_guardian_impl_detail_load_failed), announce = true)
                Body(state.error.message())
            }
            AlertDetailUiState.NotFound -> Column(Modifier.padding(horizontal = 16.dp)) {
                Headline(stringResource(R.string.feature_guardian_impl_detail_not_found), announce = true)
                Body(stringResource(R.string.feature_guardian_impl_detail_not_found_body))
            }
            is AlertDetailUiState.Ready -> Detail(state, answerable, onMessageChange)
        }
    }
}

/** 아래에 붙는 답하기 영역: 오류(있으면, 낭독) → 버튼 */
@Composable
private fun ColumnScope.AnswerBar(state: AlertDetailUiState.Ready, onDecide: (FamilyDecision) -> Unit) {
    val alert = state.alert
    val sending = state.sending != null
    state.error?.let { error ->
        Text(
            error.message(),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalBadgeColors.current.risk.ink,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LocalBadgeColors.current.risk.background).padding(12.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive },
        )
    }
    val uninstall: @Composable (Modifier) -> Unit = {
        GuardianPrimaryButton(stringResource(R.string.feature_guardian_impl_decide_uninstall), { onDecide(FamilyDecision.RECOMMEND_UNINSTALL) }, it, enabled = !sending)
    }
    if (alert.notice) {
        uninstall(Modifier)
    } else {
        // 보호자 화면: 왼쪽 보조, 오른쪽 주요 (ux-flows §1 보호자 화면)
        PdButtonPair(
            first = { GuardianOutlinedButton(stringResource(R.string.feature_guardian_impl_decide_allow), { onDecide(FamilyDecision.ALLOW) }, it, enabled = !sending) },
            second = uninstall,
            minHeight = 48.dp,
            gap = 8.dp,
        )
    }
    if (sending) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
}

/** 같이 보낼 메시지와 허용 결과 한 줄 (본문 끝, 스크롤됨) */
@Composable
private fun MessageField(state: AlertDetailUiState.Ready, onMessageChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.message,
            onValueChange = onMessageChange,
            label = { Text(stringResource(R.string.feature_guardian_impl_detail_message_label)) },
            placeholder = { Text(stringResource(R.string.feature_guardian_impl_detail_message_hint)) },
            supportingText = {
                Row(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.feature_guardian_impl_detail_message_help), modifier = Modifier.weight(1f))
                    Text("${state.message.length}/$MESSAGE_TO_SENIOR_MAX")
                }
            },
            singleLine = true,
            enabled = state.sending == null,
            modifier = Modifier.fillMaxWidth(),
        )
        // 알림 전용(이미 허용한 앱)에는 [허용]이 없다
        if (!state.alert.notice) {
            Text(stringResource(R.string.feature_guardian_impl_detail_explain), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Detail(state: AlertDetailUiState.Ready, answerable: Boolean, onMessageChange: (String) -> Unit) {
    val alert = state.alert
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppTile(open = alert.answerable, size = 56.dp)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(alert.appLabel, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            AlertBadge(alert, openText = stringResource(R.string.feature_guardian_impl_detail_open_badge))
        }
    }

    // 지금 상태: 먼저 온 답 / 방금 답함 / 거절됨 / 알림 전용 안내. 삭제 요청 안내는 결과가 오기 전까지만 (결과는 배지·진행 띠)
    val waiting = alert.outcome == null && alert.status != FamilyAlertStatus.EXPIRED
    Column(Modifier.padding(horizontal = 16.dp)) {
        when {
            state.answeredFirst != null -> StatusBox(
                stringResource(R.string.feature_guardian_impl_answered_first, state.answeredFirst.guardianName.trim().ifEmpty { stringResource(R.string.feature_guardian_impl_guardian_fallback) }),
            )
            state.answered && alert.answer?.decision == FamilyDecision.ALLOW ->
                Headline(stringResource(R.string.feature_guardian_impl_answer_sent_allow), announce = true)
            state.answered && waiting -> {
                Headline(stringResource(R.string.feature_guardian_impl_answer_sent), announce = true)
                Body(stringResource(R.string.feature_guardian_impl_answer_sent_body, phoneName(alert.linkIds, alert.seniorName)))
                Body(stringResource(R.string.feature_guardian_impl_answer_result_later))
            }
            state.rejected && alert.answerable -> ErrorText(stringResource(R.string.feature_guardian_impl_answer_rejected))
            alert.notice && alert.answerable -> Body(stringResource(R.string.feature_guardian_impl_detail_notice))
        }
        // 답할 수 없는 요청은 아래 오류 상자가 없으므로 불러오기 실패를 여기에
        if (!answerable) state.error?.let { ErrorText(it.message()) }
    }

    // 둘 다 있으면 감지 내용 위·판정 근거 아래 (ux-flows G2)
    alert.symptom?.let { Evidence(it, alert.firstInstallTime) }
    alert.verdict?.let { Verdict(it) }
    if (alert.symptom == null && alert.verdict == null) Column(Modifier.padding(horizontal = 16.dp)) { Body(stringResource(R.string.feature_guardian_impl_alert_install_scan)) }
    // 표 바로 아래: 판단은 틀릴 수 있음 + 자세히 보기 (ux-flows G2)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.feature_guardian_impl_detail_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        More(alert)
    }

    // 결과·답 (끝난 요청)
    val answer = alert.answer
    if (answer != null || alert.outcome != null || alert.status == FamilyAlertStatus.EXPIRED) {
        GuardianSectionHeader(stringResource(R.string.feature_guardian_impl_detail_progress), topPadding = 24.dp)
        GuardianBand {
            answer?.let {
                GuardianRow(
                    title = stringResource(if (it.decision == FamilyDecision.ALLOW) R.string.feature_guardian_impl_decide_allow else R.string.feature_guardian_impl_decide_uninstall),
                    subtitle = listOfNotNull(it.guardianName.trim().ifEmpty { null }, formatDateTime(it.decidedAt)).joinToString(", "),
                )
                it.message?.takeIf { m -> m.isNotBlank() }?.let { m ->
                    GuardianRowDivider()
                    GuardianRow(stringResource(R.string.feature_guardian_impl_detail_message_sent, m))
                }
                GuardianRowDivider()
            }
            GuardianRow(
                title = stringResource(R.string.feature_guardian_impl_detail_result),
                trailing = {
                    Text(
                        if (alert.outcome == null && alert.status != FamilyAlertStatus.EXPIRED) stringResource(R.string.feature_guardian_impl_detail_result_waiting) else statusLine(alert),
                        style = MaterialTheme.typography.titleSmall,
                    )
                },
            )
        }
    }

    if (answerable) MessageField(state, onMessageChange)
}

@Composable
private fun StatusBox(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** 자세히 보기: 패키지·버전·설치 경로·감지 규칙·판정 점수 (접힘) */
@Composable
private fun More(alert: FamilyAlert) {
    var open by rememberSaveable { mutableStateOf(false) }
    // 버튼 최소 폭이 없는 글자 버튼: [자세히 보기]와 [접기]가 같은 왼쪽 끝에 붙는다
    Box(Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { open = !open }, contentAlignment = Alignment.CenterStart) {
        Text(
            stringResource(if (open) R.string.feature_guardian_impl_detail_less else R.string.feature_guardian_impl_detail_more),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    if (open) {
        val lines = listOfNotNull(
            stringResource(R.string.feature_guardian_impl_detail_package, alert.packageName),
            alert.versionName?.let { stringResource(R.string.feature_guardian_impl_detail_version, it) },
            installSourceText(alert.installSource)?.let { stringResource(R.string.feature_guardian_impl_detail_source, it) },
            alert.symptom?.let { stringResource(R.string.feature_guardian_impl_detail_rule, it.detectorVersion) },
            alert.verdict?.let { stringResource(R.string.feature_guardian_impl_detail_verdict_meta, levelText(it.level), "%.2f".format(it.score), it.modelVersion) },
        )
        lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** 감지 내용 표 (ux-flows G2) */
@Composable
private fun Evidence(symptom: FamilySymptom, installedAt: Instant?) {
    GuardianSectionHeader(stringResource(R.string.feature_guardian_impl_detail_evidence_title), topPadding = 8.dp)
    GuardianBand {
        val date = runCatching { LocalDate.parse(symptom.date) }.getOrNull()
        FactRow(
            stringResource(R.string.feature_guardian_impl_detail_ads_label),
            if (date == null || date == LocalDate.now()) {
                stringResource(R.string.feature_guardian_impl_detail_ads_today, symptom.count)
            } else {
                stringResource(R.string.feature_guardian_impl_detail_ads_date, date.monthValue, date.dayOfMonth, symptom.count)
            },
            strong = true,
        )
        GuardianRowDivider()
        FactRow(stringResource(R.string.feature_guardian_impl_detail_first), formatTime(symptom.firstAt))
        GuardianRowDivider()
        FactRow(stringResource(R.string.feature_guardian_impl_detail_last), formatTime(symptom.lastAt))
        if (symptom.days >= 2) {
            GuardianRowDivider()
            FactRow(stringResource(R.string.feature_guardian_impl_detail_days_label), stringResource(R.string.feature_guardian_impl_detail_days_value, symptom.days))
        }
        installedAt?.let {
            GuardianRowDivider()
            FactRow(stringResource(R.string.feature_guardian_impl_detail_installed_label), formatDate(it))
        }
    }
}

/** 판정 근거 (설치 시점 분류기, classifier.md §4 보호자 문구) */
@Composable
private fun Verdict(verdict: FamilyVerdict) {
    GuardianSectionHeader(stringResource(R.string.feature_guardian_impl_detail_verdict_title), topPadding = 24.dp)
    GuardianBand {
        verdict.reasons.forEachIndexed { i, code ->
            if (i > 0) GuardianRowDivider()
            GuardianRow("${i + 1}. ${reasonText(code)}")
        }
    }
}

/** 표 한 줄: 왼쪽 항목(보조색), 오른쪽 값 */
@Composable
private fun FactRow(label: String, value: String, strong: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = if (strong) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge)
    }
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
    at.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

private fun formatDate(at: Instant): String =
    at.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN))

private fun formatDateTime(at: Instant): String =
    at.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M월 d일 HH:mm", Locale.KOREAN))

private val PreviewAlert = FamilyAlert(
    alertId = "a1", seniorName = "김영희", status = FamilyAlertStatus.OPEN, notice = false, fromInstallScan = false,
    appLabel = "초고속 클리너", packageName = "com.fast.cleaner", versionName = "3.2.1", installSource = "BROWSER_OR_FILE",
    firstInstallTime = Instant.parse("2026-10-03T05:14:00Z"),
    symptom = FamilySymptom(LocalDate.now().toString(), 12, 3, Instant.now(), Instant.now(), "usage-v1"),
    answer = null, outcome = null, createdAt = Instant.now(), updatedAt = Instant.now(),
)

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun DetailPreview() {
    PhoneDoctorTheme { AlertDetailScreen(AlertDetailUiState.Ready(PreviewAlert, message = "광고 앱 같아요"), {}, {}, {}, {}) }
}
