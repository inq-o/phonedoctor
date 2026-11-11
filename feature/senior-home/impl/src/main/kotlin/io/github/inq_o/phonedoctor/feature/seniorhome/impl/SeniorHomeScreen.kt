package io.github.inq_o.phonedoctor.feature.seniorhome.impl

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
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
import io.github.inq_o.phonedoctor.core.model.ScanRun
import io.github.inq_o.phonedoctor.core.model.SymptomAlert
import java.time.LocalDate

@Composable
fun SeniorHomeScreen(
    onAlertClick: (Long) -> Unit,
    onHistoryClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SeniorHomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    SeniorHomeScreen(
        state = state,
        onAlertClick = onAlertClick,
        onHistoryClick = onHistoryClick,
        onScanNow = viewModel::scanNow,
        onOpenUsageSettings = { context.startFirst(viewModel.usageAccessSettingsIntents) },
        modifier = modifier,
    )
}

private fun Context.startFirst(intents: List<Intent>) {
    for (intent in intents) {
        try {
            startActivity(intent)
            return
        } catch (e: ActivityNotFoundException) {
            continue
        }
    }
}

@Composable
internal fun SeniorHomeScreen(
    state: SeniorHomeUiState,
    onAlertClick: (Long) -> Unit,
    onHistoryClick: () -> Unit,
    onScanNow: () -> Unit,
    onOpenUsageSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val history = stringResource(R.string.feature_senior_home_impl_history)
    SeniorScreen(
        modifier = modifier,
        topBar = {
            Text(
                text = stringResource(R.string.feature_senior_home_impl_app_name),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            )
        },
        actions = {
            when (state) {
                SeniorHomeUiState.Loading -> Unit
                SeniorHomeUiState.UsageAccessOff -> {
                    PdOutlinedButton(history, onHistoryClick)
                    PdPrimaryButton(stringResource(R.string.feature_senior_home_impl_usage_open), onOpenUsageSettings)
                }
                is SeniorHomeUiState.Ready -> {
                    PdOutlinedButton(history, onHistoryClick)
                    // 확인할 앱이 있으면 주요 행동은 위의 앱 카드다 (한 화면에 주요 행동 하나)
                    if (state.alerts.isEmpty()) {
                        PdPrimaryButton(
                            text = stringResource(
                                if (state.scanning) R.string.feature_senior_home_impl_scanning else R.string.feature_senior_home_impl_scan_now,
                            ),
                            onClick = onScanNow,
                            enabled = !state.scanning,
                        )
                    }
                }
            }
        },
    ) {
        Spacer(Modifier.height(8.dp))
        when (state) {
            SeniorHomeUiState.Loading -> Unit
            SeniorHomeUiState.UsageAccessOff -> {
                StatusCircle(StatusKind.ATTENTION)
                Headline(stringResource(R.string.feature_senior_home_impl_usage_off_title))
                Body(stringResource(R.string.feature_senior_home_impl_usage_off_body))
            }
            is SeniorHomeUiState.Ready ->
                if (state.alerts.isEmpty()) {
                    StatusCircle(StatusKind.OK)
                    Headline(stringResource(R.string.feature_senior_home_impl_ok_title))
                    Body(lastScanSentence(state.lastScan))
                    Body(stringResource(R.string.feature_senior_home_impl_scope))
                    if (!state.notificationsOn) Body(stringResource(R.string.feature_senior_home_impl_notifications_off))
                } else {
                    StatusCircle(StatusKind.ATTENTION)
                    Headline(stringResource(R.string.feature_senior_home_impl_attention_title, state.alerts.size))
                    Body(stringResource(R.string.feature_senior_home_impl_attention_body))
                    state.alerts.forEach { alert ->
                        AlertCard(alert, onClick = { onAlertClick(alert.id) })
                        Spacer(Modifier.height(16.dp))
                    }
                }
        }
    }
}

@Composable
private fun lastScanSentence(run: ScanRun?): String {
    if (run == null) return stringResource(R.string.feature_senior_home_impl_last_scan_none)
    val time = zoned(run.startedAt)
    return if (time.toLocalDate() == LocalDate.now()) {
        stringResource(R.string.feature_senior_home_impl_last_scan_today, timeOfDay(time))
    } else {
        stringResource(R.string.feature_senior_home_impl_last_scan_date, time.monthValue, time.dayOfMonth, timeOfDay(time))
    }
}

@Composable
private fun Headline(text: String) {
    Spacer(Modifier.height(24.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().semantics { heading() },
    )
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(12.dp))
}

/** 카드 전체가 버튼이다 (S1′). 높이 80dp 이상 */
@Composable
private fun AlertCard(alert: SymptomAlert, onClick: () -> Unit) {
    OutlinedCard(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp).semantics { role = Role.Button },
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(alert.app.packageName, size = 56.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(alert.label, style = MaterialTheme.typography.titleLarge)
                Text(symptomSentence(alert), style = MaterialTheme.typography.bodyLarge)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

private val PreviewAlert = SymptomAlert(
    1, AppIdentity("com.example.cleaner", "00", 1), "초고속 클리너", AlertStatus.OPEN, null,
    LocalDate.now().toString(), 5, 1, 0, 0, "preview", 0, 0,
)

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun OkPreview() {
    PhoneDoctorTheme {
        SeniorHomeScreen(SeniorHomeUiState.Ready(emptyList(), null, scanning = false, notificationsOn = true), {}, {}, {}, {})
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun AttentionPreview() {
    PhoneDoctorTheme {
        SeniorHomeScreen(SeniorHomeUiState.Ready(listOf(PreviewAlert), null, scanning = false, notificationsOn = true), {}, {}, {}, {})
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720, fontScale = 2f)
@Composable
private fun UsageOffLargeFontPreview() {
    PhoneDoctorTheme {
        SeniorHomeScreen(SeniorHomeUiState.UsageAccessOff, {}, {}, {}, {})
    }
}
