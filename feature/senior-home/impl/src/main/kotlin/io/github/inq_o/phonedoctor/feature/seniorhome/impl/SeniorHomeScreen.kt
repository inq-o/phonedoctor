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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.inq_o.phonedoctor.core.designsystem.component.AppIcon
import io.github.inq_o.phonedoctor.core.designsystem.component.PdMenuGrid
import io.github.inq_o.phonedoctor.core.designsystem.component.PdMenuTile
import io.github.inq_o.phonedoctor.core.designsystem.component.PdPrimaryButton
import io.github.inq_o.phonedoctor.core.designsystem.theme.LocalStatusColors
import io.github.inq_o.phonedoctor.core.designsystem.component.SeniorScreen
import io.github.inq_o.phonedoctor.core.designsystem.component.StatusCircle
import io.github.inq_o.phonedoctor.core.designsystem.component.StatusKind
import io.github.inq_o.phonedoctor.core.designsystem.theme.PhoneDoctorTheme
import io.github.inq_o.phonedoctor.core.model.AlertStatus
import io.github.inq_o.phonedoctor.core.model.AppIdentity
import io.github.inq_o.phonedoctor.core.model.ScanRun
import io.github.inq_o.phonedoctor.core.model.SymptomAlert
import java.time.LocalDate
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import io.github.inq_o.phonedoctor.core.model.FamilyLink
import io.github.inq_o.phonedoctor.core.model.LinkStatus
import java.time.Instant

@Composable
fun SeniorHomeScreen(
    onAlertClick: (Long) -> Unit,
    onHistoryClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onFamilyClick: () -> Unit,
    onLinkRequestClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SeniorHomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val family by viewModel.family.collectAsStateWithLifecycle()
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
        family = family,
        onSettingsClick = onSettingsClick,
        onFamilyClick = onFamilyClick,
        onLinkRequestClick = onLinkRequestClick,
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
    family: HomeFamily = HomeFamily(available = false),
    onSettingsClick: () -> Unit = {},
    onFamilyClick: () -> Unit = {},
    onLinkRequestClick: (String) -> Unit = {},
) {
    SeniorScreen(
        modifier = modifier,
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.feature_senior_home_impl_app_name),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                )
            }
        },
        // 누르는 영역(주요 버튼 + 메뉴 칸 2×2)은 아래쪽에 두되 고정하지 않는다: 큰 글꼴에서 위 상태·앱 카드를 가리지 않고 같이 스크롤 (§2 예외).
        // [켜러 가기]는 메뉴 칸 위 (읽는 순서: 상태 문장 → 주요 버튼 → 나머지, §7). 칸의 자리와 이름은 상태가 바뀌어도 그대로 (원칙 2)
        footer = {
            if (state is SeniorHomeUiState.UsageAccessOff) {
                PdPrimaryButton(stringResource(R.string.feature_senior_home_impl_usage_open), onOpenUsageSettings)
            }
            // 확인할 앱이 있으면 주요 행동은 위의 앱 카드, 광고 찾기가 꺼져 있으면 [켜러 가기]다 (한 화면에 주요 행동 하나).
            // 광고 찾기가 꺼져 있거나 불러오는 중이면 [살펴보기]는 눌러도 할 일이 없어 끈다 (자리와 이름은 그대로)
            run {
                val ready = state as? SeniorHomeUiState.Ready
                HomeMenu(
                    scanFilled = ready != null && ready.alerts.isEmpty(),
                    scanEnabled = ready != null && !ready.scanning,
                    scanning = ready?.scanning == true,
                    familyAvailable = family.available,
                    onScanNow = onScanNow,
                    onHistoryClick = onHistoryClick,
                    onFamilyClick = onFamilyClick,
                    onSettingsClick = onSettingsClick,
                )
            }
        },
    ) {
        Spacer(Modifier.height(8.dp))
        if (state !is SeniorHomeUiState.Loading) {
            family.pending?.let { link ->
                LinkRequestCard(link.guardianName, onClick = { onLinkRequestClick(link.linkId) })
                Spacer(Modifier.height(16.dp))
            }
        }
        when (state) {
            SeniorHomeUiState.Loading -> Unit
            is SeniorHomeUiState.UsageAccessOff -> {
                StatusCircle(StatusKind.ATTENTION)
                Headline(stringResource(R.string.feature_senior_home_impl_usage_off_title))
                Body(stringResource(R.string.feature_senior_home_impl_usage_off_body))
                if (state.alerts.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Body(stringResource(R.string.feature_senior_home_impl_attention_title, state.alerts.size))
                    AlertCards(state.alerts, onAlertClick)
                }
            }
            is SeniorHomeUiState.Ready ->
                if (state.alerts.isEmpty()) {
                    StatusCircle(StatusKind.OK)
                    Headline(stringResource(R.string.feature_senior_home_impl_ok_title))
                    Body(lastScanSentence(state.lastScan))
                    Guardians(family.guardians)
                    if (!state.notificationsOn) Body(stringResource(R.string.feature_senior_home_impl_notifications_off))
                } else {
                    StatusCircle(StatusKind.ATTENTION)
                    Headline(stringResource(R.string.feature_senior_home_impl_attention_title, state.alerts.size))
                    Guardians(family.guardians)
                    AlertCards(state.alerts, onAlertClick)
                }
        }
    }
}

@Composable
private fun Guardians(guardians: List<String>) {
    if (guardians.isEmpty()) return
    val fallback = stringResource(R.string.feature_senior_home_impl_family)
    val names = guardians.joinToString(", ") { it.trim().ifEmpty { fallback } }
    Body(stringResource(R.string.feature_senior_home_impl_guardians, names))
}

/** 홈 메뉴 칸 (ux-flows S1·S1′). 가족 연결이 없는 빌드에서는 [가족] 자리에 [설정]이 한 줄을 다 쓴다 */
@Composable
private fun HomeMenu(
    scanFilled: Boolean,
    scanEnabled: Boolean,
    scanning: Boolean,
    familyAvailable: Boolean,
    onScanNow: () -> Unit,
    onHistoryClick: () -> Unit,
    onFamilyClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val scan: @Composable (Modifier) -> Unit = {
        PdMenuTile(
            text = stringResource(if (scanning) R.string.feature_senior_home_impl_scanning_short else R.string.feature_senior_home_impl_menu_scan),
            icon = Icons.Filled.Search,
            onClick = onScanNow,
            filled = scanFilled,
            enabled = scanEnabled,
            modifier = it,
        )
    }
    val history: @Composable (Modifier) -> Unit = {
        PdMenuTile(stringResource(R.string.feature_senior_home_impl_history_title), Icons.AutoMirrored.Filled.List, onHistoryClick, it)
    }
    val familyTile: @Composable (Modifier) -> Unit = {
        PdMenuTile(stringResource(R.string.feature_senior_home_impl_menu_family), Icons.Filled.Person, onFamilyClick, it)
    }
    val settings: @Composable (Modifier) -> Unit = {
        PdMenuTile(stringResource(R.string.feature_senior_home_impl_settings), Icons.Filled.Settings, onSettingsClick, it)
    }
    PdMenuGrid(
        rows = if (familyAvailable) listOf(listOf(scan, history), listOf(familyTile, settings)) else listOf(listOf(scan, history), listOf(settings)),
    )
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

@Composable
private fun AlertCards(alerts: List<SymptomAlert>, onAlertClick: (Long) -> Unit) {
    alerts.forEach { alert ->
        AlertCard(alert, onClick = { onAlertClick(alert.id) })
        Spacer(Modifier.height(16.dp))
    }
}

/** 카드 전체가 버튼이다 (S1′). 높이 80dp 이상 */
@Composable
private fun AlertCard(alert: SymptomAlert, onClick: () -> Unit) {
    OutlinedCard(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(3.dp, LocalStatusColors.current.caution),
        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp).semantics { role = Role.Button },
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(alert.app.packageName, size = 48.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(alert.label, style = MaterialTheme.typography.titleLarge)
                Text(symptomSentence(alert), style = MaterialTheme.typography.bodyLarge)
                // 카드를 누를 수 있다는 단서 (R1: 시니어는 글자로 된 UI를 누를 수 있다고 인식하지 못함)
                Text(
                    stringResource(R.string.feature_senior_home_impl_attention_body),
                    // 주황은 본문 글자색으로 쓰지 않는다 (ux-flows §5, 흰 바탕 5.0:1). 주황은 테두리에만
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun guardianName(name: String): String = name.trim().ifEmpty { stringResource(R.string.feature_senior_home_impl_family) }

/** 가족의 연결 요청. 카드 전체가 버튼이다 */
@Composable
private fun LinkRequestCard(name: String, onClick: () -> Unit) {
    OutlinedCard(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp).semantics { role = Role.Button },
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.feature_senior_home_impl_link_request_title, guardianName(name)), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.feature_senior_home_impl_link_request_body), style = MaterialTheme.typography.bodyLarge)
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
private fun FamilyPreview() {
    PhoneDoctorTheme {
        SeniorHomeScreen(
            SeniorHomeUiState.Ready(emptyList(), null, scanning = false, notificationsOn = true), {}, {}, {}, {},
            family = HomeFamily(
                available = true,
                guardians = listOf("민지"),
                pending = FamilyLink("l2", LinkStatus.PENDING, "엄마", "철수", Instant.now(), null, null),
            ),
        )
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
private fun AttentionLargeFontPreview() {
    PhoneDoctorTheme {
        SeniorHomeScreen(SeniorHomeUiState.Ready(listOf(PreviewAlert), null, scanning = false, notificationsOn = true), {}, {}, {}, {})
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720, fontScale = 2f)
@Composable
private fun UsageOffLargeFontPreview() {
    PhoneDoctorTheme {
        SeniorHomeScreen(SeniorHomeUiState.UsageAccessOff(listOf(PreviewAlert)), {}, {}, {}, {})
    }
}
