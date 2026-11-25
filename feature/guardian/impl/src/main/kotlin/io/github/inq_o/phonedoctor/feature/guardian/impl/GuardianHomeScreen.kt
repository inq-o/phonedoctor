package io.github.inq_o.phonedoctor.feature.guardian.impl

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianOutlinedButton
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianPrimaryButton
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianScreen
import io.github.inq_o.phonedoctor.core.designsystem.theme.GuardianTheme
import io.github.inq_o.phonedoctor.core.designsystem.theme.PhoneDoctorTheme
import io.github.inq_o.phonedoctor.core.model.FamilyLink
import io.github.inq_o.phonedoctor.core.model.LinkStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
        onRevoke = viewModel::revoke,
        onRetry = viewModel::refresh,
        onOpenNotificationSettings = { context.openNotificationSettings() },
        modifier = modifier,
    )
}

private fun Context.openNotificationSettings() {
    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
}

/** G1. 답을 기다리는 요청(누르면 G2), 지켜보는 폰, 지난 요청(G3) */
@Composable
internal fun GuardianHomeScreen(
    state: GuardianHomeUiState,
    notificationsOn: Boolean,
    onAddFamily: () -> Unit,
    onOpenRequest: (String) -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onRevoke: (FamilyLink) -> Unit,
    onRetry: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirming by rememberSaveable { mutableStateOf<String?>(null) }
    val ready = state as? GuardianHomeUiState.Ready
    GuardianScreen(
        title = stringResource(R.string.feature_guardian_impl_home_title),
        modifier = modifier,
        actions = {
            GuardianOutlinedButton(stringResource(R.string.feature_guardian_impl_settings), onSettings)
            if (state is GuardianHomeUiState.Failed) GuardianOutlinedButton(stringResource(R.string.feature_guardian_impl_retry), onRetry)
            GuardianPrimaryButton(stringResource(R.string.feature_guardian_impl_add_family), onAddFamily, enabled = ready?.revoking == null)
        },
    ) {
        if (!notificationsOn) NotificationsOffCard(onOpenNotificationSettings)
        when (state) {
            GuardianHomeUiState.Loading -> CircularProgressIndicator()
            is GuardianHomeUiState.Failed -> {
                Headline(stringResource(R.string.feature_guardian_impl_home_load_failed), announce = true)
                Body(state.error.message())
            }
            is GuardianHomeUiState.Ready -> {
                when (val message = state.message) {
                    is GuardianMessage.Revoked -> Headline(
                        stringResource(
                            if (message.wasPending) R.string.feature_guardian_impl_home_cancelled else R.string.feature_guardian_impl_home_revoked,
                            phoneName(message.linkId, message.seniorName),
                        ),
                        announce = true,
                    )
                    is GuardianMessage.Failed -> {
                        ErrorText(stringResource(R.string.feature_guardian_impl_home_revoke_failed))
                        Body(message.error.message())
                    }
                    null -> Unit
                }
                if (state.requests.isNotEmpty()) {
                    Text(
                        stringResource(R.string.feature_guardian_impl_home_requests, state.requests.size),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth().semantics { heading() },
                    )
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.requests.forEach { AlertCard(it) { onOpenRequest(it.alertId) } }
                    }
                    Spacer(Modifier.height(24.dp))
                }
                if (state.links.isEmpty()) {
                    Headline(stringResource(R.string.feature_guardian_impl_home_empty_title))
                    Body(stringResource(R.string.feature_guardian_impl_home_empty_body))
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.links.forEach { item ->
                            LinkCard(item, enabled = state.revoking == null, onRevoke = { confirming = item.link.linkId })
                        }
                    }
                }
                if (state.hasHistory) {
                    Spacer(Modifier.height(16.dp))
                    GuardianOutlinedButton(stringResource(R.string.feature_guardian_impl_home_history), onHistory)
                }
                if (state.revoking != null) CircularProgressIndicator()
            }
        }
    }
    val target = ready?.links?.firstOrNull { it.link.linkId == confirming }?.link
    if (target != null) {
        RevokeDialog(
            link = target,
            onRevoke = {
                confirming = null
                onRevoke(target)
            },
            onKeep = { confirming = null },
        )
    }
}

@Composable
private fun NotificationsOffCard(onOpenSettings: () -> Unit) {
    OutlinedCard(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.feature_guardian_impl_notifications_off_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.feature_guardian_impl_notifications_off_body), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            GuardianOutlinedButton(stringResource(R.string.feature_guardian_impl_notifications_open), onOpenSettings)
        }
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun LinkCard(item: GuardianLink, enabled: Boolean, onRevoke: () -> Unit) {
    val link = item.link
    val name = phoneName(link.linkId, link.seniorName)
    OutlinedCard(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.feature_guardian_impl_home_phone, name), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            if (link.status == LinkStatus.PENDING) {
                Text(stringResource(R.string.feature_guardian_impl_home_pending), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                GuardianOutlinedButton(stringResource(R.string.feature_guardian_impl_home_cancel_request), onRevoke, enabled = enabled)
            } else {
                Text(
                    link.lastCheckedAt?.let { stringResource(R.string.feature_guardian_impl_home_last_checked, formatTime(it)) }
                        ?: stringResource(R.string.feature_guardian_impl_home_no_check_yet),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // 시니어 폰의 보호가 반쯤 멈춤: 권한이 꺼짐 (T055a, ux-flows F6 "권한 꺼짐 표시")
                link.protection?.let { p ->
                    if (!p.usageAccess) Warning(stringResource(R.string.feature_guardian_impl_home_usage_off, name))
                    if (!p.notifications) Warning(stringResource(R.string.feature_guardian_impl_home_notifications_off, name))
                }
                if (item.contactLost) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.feature_guardian_impl_home_contact_lost, name),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(8.dp))
                GuardianOutlinedButton(stringResource(R.string.feature_guardian_impl_home_revoke), onRevoke, enabled = enabled)
            }
        }
    }
}

@Composable
private fun Warning(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

/** "오늘 오후 3:05" / "11월 20일 오후 3:05" */
@Composable
private fun formatTime(at: Instant): String {
    val zone = ZoneId.systemDefault()
    val time = at.atZone(zone)
    val clock = time.format(DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN))
    return if (time.toLocalDate() == LocalDate.now(zone)) {
        stringResource(R.string.feature_guardian_impl_today_at, clock)
    } else {
        stringResource(R.string.feature_guardian_impl_date_at, time.monthValue, time.dayOfMonth, clock)
    }
}

/** 끊기 확인. 바깥을 누르거나 뒤로 가면 그대로 둔다 */
@Composable
private fun RevokeDialog(link: FamilyLink, onRevoke: () -> Unit, onKeep: () -> Unit) {
    val name = phoneName(link.linkId, link.seniorName)
    val pending = link.status == LinkStatus.PENDING
    GuardianTheme {
        AlertDialog(
            onDismissRequest = onKeep,
            title = {
                Text(
                    stringResource(
                        if (pending) R.string.feature_guardian_impl_cancel_dialog_title else R.string.feature_guardian_impl_revoke_dialog_title,
                        name,
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (pending) R.string.feature_guardian_impl_cancel_dialog_body else R.string.feature_guardian_impl_revoke_dialog_body,
                        name,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = onRevoke) {
                    Text(
                        stringResource(if (pending) R.string.feature_guardian_impl_home_cancel_request else R.string.feature_guardian_impl_home_revoke),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onKeep) { Text(stringResource(R.string.feature_guardian_impl_keep)) }
            },
        )
    }
}

private val PreviewLinks = listOf(
    GuardianLink(
        FamilyLink("l1", LinkStatus.ACTIVE, "엄마", "민지", Instant.parse("2026-11-20T01:00:00Z"), Instant.parse("2026-11-20T01:02:00Z"), Instant.now()),
    ),
    GuardianLink(
        FamilyLink("l2", LinkStatus.ACTIVE, "아빠", "민지", Instant.parse("2026-11-10T01:00:00Z"), Instant.parse("2026-11-10T01:02:00Z"), null),
        contactLost = true,
    ),
    GuardianLink(FamilyLink("l3", LinkStatus.PENDING, "", "민지", Instant.now(), null, null)),
)

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun HomePreview() {
    PhoneDoctorTheme { GuardianHomeScreen(GuardianHomeUiState.Ready(PreviewLinks), false, {}, {}, {}, {}, {}, {}, {}) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun EmptyPreview() {
    PhoneDoctorTheme { GuardianHomeScreen(GuardianHomeUiState.Ready(emptyList()), true, {}, {}, {}, {}, {}, {}, {}) }
}
