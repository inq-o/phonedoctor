package io.github.inq_o.phonedoctor.feature.guardian.impl

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianBand
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianGap
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianOutlinedButton
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianPrimaryButton
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianRow
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianRowDivider
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianScreen
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianSectionHeader
import io.github.inq_o.phonedoctor.core.designsystem.component.StatusCircle
import io.github.inq_o.phonedoctor.core.designsystem.component.StatusKind
import io.github.inq_o.phonedoctor.core.designsystem.theme.LocalBadgeColors
import io.github.inq_o.phonedoctor.core.designsystem.theme.PhoneDoctorTheme
import io.github.inq_o.phonedoctor.core.model.FamilyAlert
import io.github.inq_o.phonedoctor.core.model.FamilyLink
import io.github.inq_o.phonedoctor.core.model.LinkStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun GuardianHomeScreen(
    onAddFamily: () -> Unit,
    onOpenRequest: (String) -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GuardianHomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var notificationsOn by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose {}
    }
    GuardianHomeScreen(
        state = state,
        notificationsOn = notificationsOn,
        onAddFamily = onAddFamily,
        onOpenRequest = onOpenRequest,
        onHistory = onHistory,
        onSettings = onSettings,
        onRetry = viewModel::refresh,
        onOpenNotificationSettings = { context.openNotificationSettings() },
        modifier = modifier,
    )
}

private fun Context.openNotificationSettings() {
    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
}

/** 지켜보는 폰 하나의 상태 (ux-flows §4 G1): 오프라인 → 확인 필요 N건 → 확인할 앱 없음. 수락 전은 수락 대기 */
internal sealed interface PhoneStatus {
    data object Pending : PhoneStatus
    data object Offline : PhoneStatus
    data class Attention(val count: Int) : PhoneStatus
    data object Ok : PhoneStatus
}

internal fun phoneStatus(item: GuardianLink, requests: List<FamilyAlert>): PhoneStatus = when {
    item.link.status == LinkStatus.PENDING -> PhoneStatus.Pending
    item.contactLost -> PhoneStatus.Offline
    else -> requests.count { item.link.linkId in it.linkIds }.let { if (it > 0) PhoneStatus.Attention(it) else PhoneStatus.Ok }
}

/**
 * G1 보호자 홈 (ux-flows §4 G1): 위 막대(폰주치의 · 설정 톱니), 지켜보는 폰 상태, 답변할 요청, 지난 요청·폰 추가 연결.
 * 연결 끊기는 G4 설정에서 (홈에 바로 두지 않음)
 */
@Composable
internal fun GuardianHomeScreen(
    state: GuardianHomeUiState,
    notificationsOn: Boolean,
    onAddFamily: () -> Unit,
    onOpenRequest: (String) -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onRetry: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = state as? GuardianHomeUiState.Ready
    val noLinks = ready != null && ready.links.isEmpty()
    GuardianScreen(
        title = stringResource(R.string.feature_guardian_impl_home_title),
        large = true,
        modifier = modifier,
        topActions = {
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.feature_guardian_impl_settings))
            }
        },
        contentPadding = 0.dp,
        actions = when {
            state is GuardianHomeUiState.Failed -> ({ GuardianOutlinedButton(stringResource(R.string.feature_guardian_impl_retry), onRetry) })
            // 연결된 폰이 없을 때만 [폰 연결]이 화면의 주요 행동 (연결이 있으면 목록의 "폰 추가 연결")
            noLinks -> ({ GuardianPrimaryButton(stringResource(R.string.feature_guardian_impl_add_family), onAddFamily) })
            else -> null
        },
    ) {
        if (!notificationsOn) {
            NotificationsOff(onOpenNotificationSettings)
            GuardianGap(16.dp)
        }
        when (state) {
            GuardianHomeUiState.Loading -> CircularProgressIndicator(Modifier.padding(16.dp))
            is GuardianHomeUiState.Failed -> Padded {
                Headline(stringResource(R.string.feature_guardian_impl_home_load_failed), announce = true)
                Body(state.error.message())
            }
            is GuardianHomeUiState.Ready -> if (state.links.isEmpty()) {
                Padded {
                    Headline(stringResource(R.string.feature_guardian_impl_home_empty_title))
                    Body(stringResource(R.string.feature_guardian_impl_home_empty_body))
                }
            } else {
                ReadyContent(state, onOpenRequest, onHistory, onSettings, onAddFamily)
            }
        }
    }
}

@Composable
private fun ReadyContent(
    state: GuardianHomeUiState.Ready,
    onOpenRequest: (String) -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onAddFamily: () -> Unit,
) {
    val single = state.links.singleOrNull()?.takeIf { it.link.status == LinkStatus.ACTIVE }
    if (single != null) {
        PhoneHeader(single, phoneStatus(single, state.requests))
    } else {
        GuardianSectionHeader(stringResource(R.string.feature_guardian_impl_home_watching))
        GuardianBand {
            state.links.forEachIndexed { i, item ->
                if (i > 0) GuardianRowDivider()
                PhoneRow(item, phoneStatus(item, state.requests))
            }
        }
    }
    // 표시 이름을 아직 고르지 않은 연결이 있으면 설정으로 (ux-flows §4 G1)
    val names = LocalChosenPhoneNames.current
    if (state.links.any { it.link.status == LinkStatus.ACTIVE && it.link.linkId !in names }) {
        GuardianGap(8.dp)
        GuardianBand { GuardianRow(stringResource(R.string.feature_guardian_impl_home_name_prompt), onClick = onSettings) }
    }

    GuardianGap()
    GuardianSectionHeader(stringResource(R.string.feature_guardian_impl_home_requests_title))
    GuardianBand {
        if (state.requests.isEmpty()) {
            GuardianRow(stringResource(R.string.feature_guardian_impl_home_requests_empty))
        } else {
            val showPhone = state.links.count { it.link.status == LinkStatus.ACTIVE } > 1
            state.requests.forEachIndexed { i, alert ->
                if (i > 0) GuardianRowDivider()
                AlertRow(alert, showPhone = showPhone, onClick = { onOpenRequest(alert.alertId) }, badge = alert.notice)
            }
        }
    }

    GuardianGap()
    GuardianBand {
        if (state.hasHistory) {
            GuardianRow(stringResource(R.string.feature_guardian_impl_home_history), onClick = onHistory)
            GuardianRowDivider()
        }
        GuardianRow(
            stringResource(R.string.feature_guardian_impl_home_add_more),
            onClick = onAddFamily,
            chevron = false,
            trailing = { Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        )
    }
    GuardianGap(16.dp)
}

/** 폰이 하나면 상태를 크게 (시니어 폰과 같은 상태 원·색이라 전화로 "주황 동그라미 보이죠?"라고 설명할 수 있다) */
@Composable
private fun PhoneHeader(item: GuardianLink, status: PhoneStatus) {
    val link = item.link
    val name = phoneName(link.linkId, link.seniorName)
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatusCircle(if (status == PhoneStatus.Ok) StatusKind.OK else StatusKind.ATTENTION, size = 56.dp)
            Column {
                Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    statusText(status),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics {
                        heading()
                        liveRegion = LiveRegionMode.Polite
                    },
                )
                Text(lastChecked(link), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        PhoneWarnings(item, status, name)
    }
}

/** 폰이 여럿이거나 수락 전 연결이 있으면 폰마다 한 줄 */
@Composable
private fun PhoneRow(item: GuardianLink, status: PhoneStatus) {
    val link = item.link
    val name = if (status == PhoneStatus.Pending) {
        phoneName(null, link.seniorName, unnamed = R.string.feature_guardian_impl_phone_other)
    } else {
        phoneName(link.linkId, link.seniorName)
    }
    Column {
        GuardianRow(
            title = statusText(status),
            subtitle = if (status == PhoneStatus.Pending) name else "$name, ${lastChecked(link)}",
            emphasized = true,
            leading = if (status == PhoneStatus.Pending) null else ({ StatusCircle(if (status == PhoneStatus.Ok) StatusKind.OK else StatusKind.ATTENTION, size = 40.dp) }),
        )
        Column(Modifier.padding(start = 16.dp, end = 16.dp)) { PhoneWarnings(item, status, name) }
    }
}

@Composable
private fun PhoneWarnings(item: GuardianLink, status: PhoneStatus, name: String) {
    var howto by rememberSaveable(item.link.linkId) { mutableStateOf(false) }
    if (status == PhoneStatus.Offline) {
        Text(
            stringResource(R.string.feature_guardian_impl_home_offline_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        TextButton(onClick = { howto = !howto }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text(stringResource(R.string.feature_guardian_impl_home_offline_howto), style = MaterialTheme.typography.labelLarge)
        }
        if (howto) Text(stringResource(R.string.feature_guardian_impl_home_offline_steps), style = MaterialTheme.typography.bodyMedium)
    }
    // 시니어 폰의 보호가 반쯤 멈춤: 권한이 꺼짐 (T055a, ux-flows F6)
    item.link.protection?.takeIf { status != PhoneStatus.Pending }?.let { p ->
        if (!p.usageAccess) Warning(stringResource(R.string.feature_guardian_impl_home_usage_off, name))
        if (!p.notifications) Warning(stringResource(R.string.feature_guardian_impl_home_notifications_off, name))
    }
}

@Composable
private fun statusText(status: PhoneStatus): String = when (status) {
    PhoneStatus.Pending -> stringResource(R.string.feature_guardian_impl_home_status_pending)
    PhoneStatus.Offline -> stringResource(R.string.feature_guardian_impl_home_status_offline)
    is PhoneStatus.Attention -> stringResource(R.string.feature_guardian_impl_home_status_attention, status.count)
    PhoneStatus.Ok -> stringResource(R.string.feature_guardian_impl_home_status_ok)
}

@Composable
private fun lastChecked(link: FamilyLink): String =
    link.lastCheckedAt?.let { stringResource(R.string.feature_guardian_impl_home_last_checked, checkedTime(it)) }
        ?: stringResource(R.string.feature_guardian_impl_home_no_check_yet)

@Composable
private fun Warning(text: String) {
    // 14sp 경고도 본문 7:1 (§7): 배지 실패 글자색(라이트 #7F1D1D)
    Text(text, style = MaterialTheme.typography.bodyMedium, color = LocalBadgeColors.current.risk.ink, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun NotificationsOff(onOpenSettings: () -> Unit) {
    GuardianBand {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.feature_guardian_impl_notifications_off_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.feature_guardian_impl_notifications_off_body), style = MaterialTheme.typography.bodyMedium)
            GuardianOutlinedButton(stringResource(R.string.feature_guardian_impl_notifications_open), onOpenSettings)
        }
    }
}

@Composable
private fun Padded(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** "오늘 15:05" / "11월 20일 15:05" (시안 v3: 시각은 24시간 짧게) */
@Composable
private fun checkedTime(at: Instant): String {
    val zone = ZoneId.systemDefault()
    val time = at.atZone(zone)
    val clock = time.format(DateTimeFormatter.ofPattern("HH:mm"))
    return if (time.toLocalDate() == LocalDate.now(zone)) {
        stringResource(R.string.feature_guardian_impl_today_at, clock)
    } else {
        stringResource(R.string.feature_guardian_impl_date_at, time.monthValue, time.dayOfMonth, clock)
    }
}

private val PreviewLinks = listOf(
    GuardianLink(
        FamilyLink("l1", LinkStatus.ACTIVE, "김영희", "민지", Instant.parse("2026-11-20T01:00:00Z"), Instant.parse("2026-11-20T01:02:00Z"), Instant.now()),
    ),
    GuardianLink(
        FamilyLink("l2", LinkStatus.ACTIVE, "", "민지", Instant.parse("2026-11-10T01:00:00Z"), Instant.parse("2026-11-10T01:02:00Z"), null),
        contactLost = true,
    ),
    GuardianLink(FamilyLink("l3", LinkStatus.PENDING, "", "민지", Instant.now(), null, null)),
)

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun HomePreview() {
    PhoneDoctorTheme { GuardianHomeScreen(GuardianHomeUiState.Ready(PreviewLinks), false, {}, {}, {}, {}, {}, {}) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun SinglePreview() {
    PhoneDoctorTheme { GuardianHomeScreen(GuardianHomeUiState.Ready(PreviewLinks.take(1)), true, {}, {}, {}, {}, {}, {}) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun EmptyPreview() {
    PhoneDoctorTheme { GuardianHomeScreen(GuardianHomeUiState.Ready(emptyList()), true, {}, {}, {}, {}, {}, {}) }
}
