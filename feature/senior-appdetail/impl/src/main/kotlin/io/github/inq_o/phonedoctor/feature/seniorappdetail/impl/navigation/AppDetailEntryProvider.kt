package io.github.inq_o.phonedoctor.feature.seniorappdetail.impl.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import io.github.inq_o.phonedoctor.feature.seniorappdetail.api.navigation.AppDetailNavKey
import io.github.inq_o.phonedoctor.feature.seniorappdetail.impl.AppDetailScreen

fun EntryProviderScope<NavKey>.appDetailEntry(onBack: () -> Unit) {
    entry<AppDetailNavKey> { key ->
        AppDetailScreen(alertId = key.alertId, onBack = onBack)
    }
}
