package io.github.inq_o.phonedoctor.feature.seniorhome.impl.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import io.github.inq_o.phonedoctor.feature.seniorhome.api.navigation.ScanHistoryNavKey
import io.github.inq_o.phonedoctor.feature.seniorhome.api.navigation.SeniorHomeNavKey
import io.github.inq_o.phonedoctor.feature.seniorhome.api.navigation.SeniorSettingsNavKey
import io.github.inq_o.phonedoctor.feature.seniorhome.impl.ScanHistoryScreen
import io.github.inq_o.phonedoctor.feature.seniorhome.impl.SeniorHomeScreen
import io.github.inq_o.phonedoctor.feature.seniorhome.impl.SeniorSettingsScreen

/**
 * @param onSettingsClick 홈 [설정] → S7
 * @param onFamilyClick S7 [연결한 가족 보기] → S5
 */
fun EntryProviderScope<NavKey>.seniorHomeEntries(
    onAlertClick: (Long) -> Unit,
    onHistoryClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSitesClick: () -> Unit,
    onFamilyClick: () -> Unit,
    onLinkRequestClick: (String) -> Unit,
    onBack: () -> Unit,
) {
    entry<SeniorHomeNavKey> {
        SeniorHomeScreen(
            onAlertClick = onAlertClick,
            onHistoryClick = onHistoryClick,
            onSettingsClick = onSettingsClick,
            onLinkRequestClick = onLinkRequestClick,
        )
    }
    entry<ScanHistoryNavKey> {
        ScanHistoryScreen(onBack = onBack)
    }
    entry<SeniorSettingsNavKey> {
        SeniorSettingsScreen(onFamilyClick = onFamilyClick, onSitesClick = onSitesClick, onBack = onBack)
    }
}
