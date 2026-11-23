package io.github.inq_o.phonedoctor.feature.seniorsites.impl

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import io.github.inq_o.phonedoctor.core.designsystem.component.PdOutlinedButton
import io.github.inq_o.phonedoctor.core.designsystem.component.PdPrimaryButton
import io.github.inq_o.phonedoctor.core.designsystem.component.SeniorScreen
import io.github.inq_o.phonedoctor.core.designsystem.component.StatusCircle
import io.github.inq_o.phonedoctor.core.designsystem.component.StatusKind
import io.github.inq_o.phonedoctor.core.designsystem.theme.PhoneDoctorTheme
import io.github.inq_o.phonedoctor.core.model.SiteAlert
import io.github.inq_o.phonedoctor.core.model.SiteAlertStatus
import java.time.LocalDate

/**
 * @param openRequest 알림을 탭해 들어옴: 화면이 뜨자마자 그 사이트 알림 설정을 연다 (알림마다 한 번만, FR-052)
 */
@Composable
fun SiteAlertScreen(
    site: String,
    openRequest: String?,
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SiteAlertViewModel = hiltViewModel<SiteAlertViewModel, SiteAlertViewModel.Factory>(
        key = "site-$site",
        creationCallback = { it.create(site) },
    ),
) {
    val state by viewModel.alert.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        viewModel.onResumed()
        onPauseOrDispose {}
    }
    val openChannel: (SiteAlert) -> Unit = { alert ->
        viewModel.onSettingsOpened()
        context.startFirst(channelSettings(alert))
    }
    LaunchedEffect(state, openRequest) {
        val alert = (state as? SiteAlertState.Ready)?.alert ?: return@LaunchedEffect
        if (viewModel.shouldAutoOpen(openRequest) && alert.status != SiteAlertStatus.ALLOWED) {
            viewModel.onSettingsOpened(openRequest)
            context.startFirst(channelSettings(alert))
        }
    }
    SiteAlertScreen(state = state, onTurnOff = openChannel, onAllow = viewModel::allow, onHome = onHome, modifier = modifier)
}

/**
 * 그 사이트 하나의 알림 설정 화면 (스위치 하나). 브라우저가 채널을 지원하지 않거나 화면이 없으면 브라우저 알림 설정 전체
 */
private fun channelSettings(alert: SiteAlert): List<Intent> = listOf(
    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, alert.browserPackage)
        .putExtra(Settings.EXTRA_CHANNEL_ID, alert.channelId),
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, alert.browserPackage),
)

@Composable
internal fun SiteAlertScreen(
    state: SiteAlertState,
    onTurnOff: (SiteAlert) -> Unit,
    onAllow: () -> Unit,
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val alert = (state as? SiteAlertState.Ready)?.alert
    SeniorScreen(
        modifier = modifier,
        actions = {
            when (alert?.status) {
                SiteAlertStatus.ASKED, SiteAlertStatus.STILL_ON -> {
                    PdOutlinedButton(stringResource(R.string.feature_senior_sites_impl_allow), onAllow)
                    PdPrimaryButton(stringResource(R.string.feature_senior_sites_impl_turn_off), { onTurnOff(alert) })
                }
                SiteAlertStatus.MUTED -> {
                    PdOutlinedButton(stringResource(R.string.feature_senior_sites_impl_open_again), { onTurnOff(alert) })
                    PdPrimaryButton(stringResource(R.string.feature_senior_sites_impl_home), onHome)
                }
                SiteAlertStatus.ALLOWED, null -> if (state != SiteAlertState.Loading) PdPrimaryButton(stringResource(R.string.feature_senior_sites_impl_home), onHome)
            }
        },
    ) {
        Spacer(Modifier.height(8.dp))
        when {
            state == SiteAlertState.Loading -> CircularProgressIndicator()
            alert == null -> Headline(stringResource(R.string.feature_senior_sites_impl_missing))
            // 스위치를 실제로 껐는지는 알 수 없다: 다음 알림이 오는지로만 안다 (FR-053). 그래서 "껐어요"라고 단정하지 않는다
            alert.status == SiteAlertStatus.MUTED -> {
                Headline(stringResource(R.string.feature_senior_sites_impl_muted_title), announce = true)
                Body(stringResource(R.string.feature_senior_sites_impl_muted_body, alert.displayName))
            }
            alert.status == SiteAlertStatus.ALLOWED -> {
                StatusCircle(StatusKind.OK)
                Headline(stringResource(R.string.feature_senior_sites_impl_allowed_title, alert.displayName), announce = true)
                Body(stringResource(R.string.feature_senior_sites_impl_allowed_body))
            }
            else -> {
                Headline(
                    stringResource(
                        if (alert.status == SiteAlertStatus.STILL_ON) R.string.feature_senior_sites_impl_still_on_title else R.string.feature_senior_sites_impl_ask_title,
                        alert.displayName,
                    ),
                )
                Body(countSentence(alert))
                Body(stringResource(R.string.feature_senior_sites_impl_ask_how))
                Body(stringResource(R.string.feature_senior_sites_impl_ask_allow))
            }
        }
    }
}

@Composable
private fun countSentence(alert: SiteAlert): String {
    val date = runCatching { LocalDate.parse(alert.date) }.getOrNull()
    return if (date == null || date == LocalDate.now()) {
        stringResource(R.string.feature_senior_sites_impl_count_today, alert.count)
    } else {
        stringResource(R.string.feature_senior_sites_impl_count_date, date.monthValue, date.dayOfMonth, alert.count)
    }
}

@Composable
private fun Headline(text: String, announce: Boolean = false) {
    Spacer(Modifier.height(16.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics {
            heading()
            if (announce) liveRegion = LiveRegionMode.Polite
        },
    )
    Spacer(Modifier.height(16.dp))
}

private val PreviewAlert = SiteAlert("https://ads.example.com", "com.android.chrome", "web:https://ads.example.com;1", SiteAlertStatus.ASKED, LocalDate.now().toString(), 7)

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun AskPreview() {
    PhoneDoctorTheme { SiteAlertScreen(SiteAlertState.Ready(PreviewAlert), {}, {}, {}) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun MutedPreview() {
    PhoneDoctorTheme { SiteAlertScreen(SiteAlertState.Ready(PreviewAlert.copy(status = SiteAlertStatus.MUTED)), {}, {}, {}) }
}
