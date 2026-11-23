package io.github.inq_o.phonedoctor.feature.guardian.impl

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.inq_o.phonedoctor.core.model.FamilyAlert
import io.github.inq_o.phonedoctor.core.model.FamilyAlertStatus
import io.github.inq_o.phonedoctor.core.model.FamilyDecision
import io.github.inq_o.phonedoctor.core.model.FamilyOutcome
import io.github.inq_o.phonedoctor.core.model.FamilySymptom
import io.github.inq_o.phonedoctor.core.model.Josa
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
    val senior = seniorName(alert.seniorName)
    val app = alert.appLabel
    return when {
        alert.outcome != null -> when (alert.outcome) {
            FamilyOutcome.UNINSTALLED -> stringResource(R.string.feature_guardian_impl_outcome_uninstalled, senior, Josa.eulReul(app))
            FamilyOutcome.DECLINED -> stringResource(R.string.feature_guardian_impl_outcome_declined, senior, Josa.eulReul(app))
            FamilyOutcome.ALREADY_REMOVED -> stringResource(R.string.feature_guardian_impl_outcome_already_removed, Josa.eunNeun(app))
            FamilyOutcome.ALLOWED -> stringResource(R.string.feature_guardian_impl_outcome_allowed, Josa.eunNeun(app))
            FamilyOutcome.SUSPENDED -> stringResource(R.string.feature_guardian_impl_outcome_suspended, senior, Josa.eulReul(app))
            else -> stringResource(R.string.feature_guardian_impl_outcome_other)
        }
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

/** 요청 카드: 앱 이름, "엄마 폰 · 오늘 광고 12번", 상태. 누르면 G2 */
@Composable
internal fun AlertCard(alert: FamilyAlert, onClick: () -> Unit) {
    OutlinedCard(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(alert.appLabel, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.feature_guardian_impl_alert_where, seniorName(alert.seniorName), evidenceLine(alert.symptom)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (alert.notice) stringResource(R.string.feature_guardian_impl_alert_warning_app) + " · " + statusLine(alert) else statusLine(alert),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
