package io.github.inq_o.phonedoctor.feature.seniorsites.impl

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.inq_o.phonedoctor.core.designsystem.component.PdOutlinedButton
import io.github.inq_o.phonedoctor.core.designsystem.component.PdPrimaryButton
import io.github.inq_o.phonedoctor.core.designsystem.component.SeniorScreen
import io.github.inq_o.phonedoctor.core.designsystem.theme.PhoneDoctorTheme
import io.github.inq_o.phonedoctor.core.model.SiteAlert
import io.github.inq_o.phonedoctor.core.model.SiteAlertStatus
import io.github.inq_o.phonedoctor.feature.seniorsites.api.SiteAlertsFeature

@Composable
fun SitesScreen(
    onOpenSite: (String) -> Unit,
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SitesViewModel = hiltViewModel(),
) {
    val sites by viewModel.sites.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var granted by remember { mutableStateOf(SiteAlertsFeature.isGranted(context)) }
    // 설정에서 허용하고 돌아오면 바로 목록으로
    LifecycleResumeEffect(Unit) {
        granted = SiteAlertsFeature.isGranted(context)
        onPauseOrDispose {}
    }
    SitesScreen(
        granted = granted,
        sites = sites,
        onAllowAccess = { context.startFirst(SiteAlertsFeature.settingsIntents(context)) },
        onOpenSite = onOpenSite,
        onHome = onHome,
        modifier = modifier,
    )
}

internal fun Context.startFirst(intents: List<Intent>) {
    for (intent in intents) {
        try {
            startActivity(intent)
            return
        } catch (e: ActivityNotFoundException) {
            continue
        }
    }
}

/**
 * 사이트 광고 알림. 알림 접근을 허용했으면 찾은 사이트 목록, 아니면 끄는 법 안내 (S6, FR-040).
 * 두 경우 모두 Chrome의 팝업·침입 광고 차단을 켜는 법도 안내한다 (FR-055)
 */
@Composable
internal fun SitesScreen(
    granted: Boolean,
    sites: List<SiteAlert>?,
    onAllowAccess: () -> Unit,
    onOpenSite: (String) -> Unit,
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SeniorScreen(
        modifier = modifier,
        topBar = {
            Text(
                stringResource(R.string.feature_senior_sites_impl_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp).semantics { heading() },
            )
        },
        actions = {
            if (granted) {
                PdPrimaryButton(stringResource(R.string.feature_senior_sites_impl_home), onHome)
            } else {
                PdOutlinedButton(stringResource(R.string.feature_senior_sites_impl_home), onHome)
                PdPrimaryButton(stringResource(R.string.feature_senior_sites_impl_allow_access), onAllowAccess)
            }
        },
    ) {
        if (!granted) {
            Body(stringResource(R.string.feature_senior_sites_impl_access_body))
            Body(stringResource(R.string.feature_senior_sites_impl_access_scope))
            Section(stringResource(R.string.feature_senior_sites_impl_manual_title)) {
                Body(stringResource(R.string.feature_senior_sites_impl_manual_steps))
            }
        } else {
            when {
                sites == null -> CircularProgressIndicator()
                sites.isEmpty() -> Body(stringResource(R.string.feature_senior_sites_impl_empty))
                else -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    sites.forEach { SiteCard(it) { onOpenSite(it.site) } }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        Section(stringResource(R.string.feature_senior_sites_impl_popup_title)) {
            Body(stringResource(R.string.feature_senior_sites_impl_popup_steps))
        }
    }
}

@Composable
private fun SiteCard(site: SiteAlert, onClick: () -> Unit) {
    OutlinedCard(
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(site.displayName, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(
                    when (site.status) {
                        SiteAlertStatus.ASKED -> R.string.feature_senior_sites_impl_status_asked
                        SiteAlertStatus.MUTED -> R.string.feature_senior_sites_impl_status_muted
                        SiteAlertStatus.ALLOWED -> R.string.feature_senior_sites_impl_status_allowed
                        SiteAlertStatus.STILL_ON -> R.string.feature_senior_sites_impl_status_still_on
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(8.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth().semantics { heading() })
    Spacer(Modifier.height(8.dp))
    content()
}

@Composable
internal fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(12.dp))
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun GuidePreview() {
    PhoneDoctorTheme { SitesScreen(granted = false, sites = null, onAllowAccess = {}, onOpenSite = {}, onHome = {}) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800, fontScale = 2f)
@Composable
private fun ListLargeFontPreview() {
    PhoneDoctorTheme {
        SitesScreen(
            granted = true,
            sites = listOf(SiteAlert("https://ads.example.com", "com.android.chrome", "web:https://ads.example.com;1", SiteAlertStatus.ASKED, "2026-11-23", 7)),
            onAllowAccess = {}, onOpenSite = {}, onHome = {},
        )
    }
}
