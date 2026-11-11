package io.github.inq_o.phonedoctor.feature.seniorhome.impl.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import io.github.inq_o.phonedoctor.feature.seniorhome.api.navigation.ScanHistoryNavKey
import io.github.inq_o.phonedoctor.feature.seniorhome.api.navigation.SeniorHomeNavKey
import io.github.inq_o.phonedoctor.feature.seniorhome.impl.ScanHistoryScreen
import io.github.inq_o.phonedoctor.feature.seniorhome.impl.SeniorHomeScreen

fun EntryProviderScope<NavKey>.seniorHomeEntries(
    onAlertClick: (Long) -> Unit,
    onHistoryClick: () -> Unit,
    onBack: () -> Unit,
) {
    entry<SeniorHomeNavKey> {
        SeniorHomeScreen(onAlertClick = onAlertClick, onHistoryClick = onHistoryClick)
    }
    entry<ScanHistoryNavKey> {
        ScanHistoryScreen(onBack = onBack)
    }
}
