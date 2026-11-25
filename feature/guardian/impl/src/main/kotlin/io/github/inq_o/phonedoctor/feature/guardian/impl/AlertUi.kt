package io.github.inq_o.phonedoctor.feature.guardian.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianBadge
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianRow
import io.github.inq_o.phonedoctor.core.designsystem.theme.LocalBadgeColors
import io.github.inq_o.phonedoctor.core.model.FamilyAlert
import io.github.inq_o.phonedoctor.core.model.FamilyAlertStatus
import io.github.inq_o.phonedoctor.core.model.FamilyDecision
import io.github.inq_o.phonedoctor.core.model.FamilyOutcome
import io.github.inq_o.phonedoctor.core.model.FamilySymptom
import java.time.LocalDate

/** 증상 근거 한 줄: "오늘 광고 12번" / "10월 23일 광고 12번". 근거 날짜가 오늘일 때만 "오늘" (ux-flows "오늘" 표기 규칙) */
@Composable
internal fun evidenceLine(symptom: FamilySymptom?): String {
    symptom ?: return stringResource(R.string.feature_guardian_impl_alert_install_scan)
    val date = runCatching { LocalDate.parse(symptom.date) }.getOrNull()
    return if (date == null || date == LocalDate.now()) {
        stringResource(R.string.feature_guardian_impl_alert_today, symptom.count)
    } else {
        stringResource(R.string.feature_guardian_impl_alert_date, date.monthValue, date.dayOfMonth, symptom.count)
    }
}

/** 요청이 지금 어떤 상태인지 한 줄 */
@Composable
internal fun statusLine(alert: FamilyAlert): String {
    val answer = alert.answer
    // 결과는 짧은 명사 (ux-flows §6 보호자 문구). 앱 이름과 폰은 같은 줄·화면에 이미 있다
    return when {
        alert.outcome != null -> stringResource(
            when (alert.outcome) {
                FamilyOutcome.UNINSTALLED -> R.string.feature_guardian_impl_outcome_uninstalled
                FamilyOutcome.DECLINED -> R.string.feature_guardian_impl_outcome_declined
                FamilyOutcome.ALREADY_REMOVED -> R.string.feature_guardian_impl_outcome_already_removed
                FamilyOutcome.ALLOWED -> R.string.feature_guardian_impl_outcome_allowed
                FamilyOutcome.SUSPENDED -> R.string.feature_guardian_impl_outcome_suspended
                else -> R.string.feature_guardian_impl_outcome_other
            },
        )
        answer != null -> stringResource(
            if (answer.decision == FamilyDecision.RECOMMEND_UNINSTALL) {
                R.string.feature_guardian_impl_answer_uninstall
            } else {
                R.string.feature_guardian_impl_answer_allow
            },
            answer.guardianName.trim().ifEmpty { stringResource(R.string.feature_guardian_impl_guardian_fallback) },
        )
        alert.status == FamilyAlertStatus.EXPIRED -> stringResource(R.string.feature_guardian_impl_alert_expired)
        alert.notice -> stringResource(R.string.feature_guardian_impl_alert_notice)
        else -> stringResource(R.string.feature_guardian_impl_alert_waiting)
    }
}

/** 요청의 배지 (ux-flows §5 배지 토큰, G3 결과 배지 하나). [openText]: 아직 아무도 답하지 않았을 때 (G2 "확인 필요", G3 "답변 대기") */
@Composable
internal fun AlertBadge(alert: FamilyAlert, openText: String = stringResource(R.string.feature_guardian_impl_alert_waiting)) {
    val badges = LocalBadgeColors.current
    val answer = alert.answer
    val (text, color) = when {
        alert.outcome != null -> statusLine(alert) to if (alert.outcome == FamilyOutcome.ALLOWED) badges.safe else badges.neutral
        answer?.decision == FamilyDecision.RECOMMEND_UNINSTALL -> stringResource(R.string.feature_guardian_impl_decide_uninstall) to badges.info
        answer?.decision == FamilyDecision.ALLOW -> stringResource(R.string.feature_guardian_impl_decide_allow) to badges.safe
        alert.status == FamilyAlertStatus.EXPIRED -> stringResource(R.string.feature_guardian_impl_alert_expired) to badges.neutral
        alert.notice -> stringResource(R.string.feature_guardian_impl_alert_warning_app) to badges.safe
        else -> openText to badges.caution
    }
    GuardianBadge(text, color)
}

/** 앱 아이콘 자리 (보호자 폰에는 그 앱이 없어 아이콘을 받지 않는다): 확인 필요는 주황 면, 끝난 것은 회색 면 */
@Composable
internal fun AppTile(open: Boolean, size: Dp = 44.dp) {
    val badges = LocalBadgeColors.current
    val color = if (open) badges.caution else badges.neutral
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(color.background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = color.ink, modifier = Modifier.size(size / 2))
    }
}

/**
 * 요청 한 줄 (G1 답변할 요청, G3 지난 요청): 앱 이름 / "{폰}, 오늘 광고 12회" / 배지 / ›.
 * 폰이 하나뿐이면 [showPhone]을 끈다
 */
@Composable
internal fun AlertRow(alert: FamilyAlert, showPhone: Boolean, onClick: () -> Unit, badge: Boolean = true) {
    val evidence = evidenceLine(alert.symptom)
    GuardianRow(
        title = alert.appLabel,
        subtitle = if (showPhone) {
            stringResource(R.string.feature_guardian_impl_alert_where, phoneName(alert.linkIds, alert.seniorName), evidence)
        } else {
            evidence
        },
        emphasized = true,
        onClick = onClick,
        leading = { AppTile(open = alert.answerable) },
        trailing = if (badge) ({ AlertBadge(alert) }) else null,
    )
}
