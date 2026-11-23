package io.github.inq_o.phonedoctor.feature.seniorsites.impl.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import io.github.inq_o.phonedoctor.feature.seniorsites.api.navigation.SiteAlertNavKey
import io.github.inq_o.phonedoctor.feature.seniorsites.api.navigation.SitesNavKey
import io.github.inq_o.phonedoctor.feature.seniorsites.impl.SiteAlertScreen
import io.github.inq_o.phonedoctor.feature.seniorsites.impl.SitesScreen

/**
 * @param onOpenSite 목록의 사이트 → 그 사이트 화면
 * @param onHome [홈으로]
 */
fun EntryProviderScope<NavKey>.seniorSitesEntries(
    onOpenSite: (String) -> Unit,
    onHome: () -> Unit,
    onBack: () -> Unit,
) {
    entry<SitesNavKey> {
        SitesScreen(onOpenSite = onOpenSite, onBack = onBack)
    }
    entry<SiteAlertNavKey> { key ->
        SiteAlertScreen(site = key.site, openSettings = key.openSettings, onHome = onHome)
    }
}
