package io.github.inq_o.phonedoctor.feature.seniorsites.impl

import androidx.lifecycle.SavedStateHandle
import io.github.inq_o.phonedoctor.core.data.SiteAlertRepository
import io.github.inq_o.phonedoctor.core.data.SiteNotice
import io.github.inq_o.phonedoctor.core.model.FamilyDecision
import io.github.inq_o.phonedoctor.core.model.SiteAlert
import io.github.inq_o.phonedoctor.core.model.SiteAlertStatus
import io.github.inq_o.phonedoctor.core.model.SymptomAlert
import io.github.inq_o.phonedoctor.core.notifications.GuardianAlertMessage
import io.github.inq_o.phonedoctor.core.notifications.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SiteAlertViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val repo = FakeSites()
    private val notifier = RecordingNotifier()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun openingTheScreenClearsTheNotificationAndTracksTheChoice() = runTest(dispatcher) {
        repo.sites.value = mapOf(SITE to alert(SiteAlertStatus.ASKED))
        val vm = SiteAlertViewModel(SITE, repo, notifier, SavedStateHandle())
        backgroundScope.launch { vm.alert.collect {} }
        assertEquals(listOf(SITE), notifier.cancelled)
        assertEquals(SiteAlertStatus.ASKED, (vm.alert.value as SiteAlertState.Ready).alert.status)

        // 설정을 연 동안은 그대로, 돌아와야 끄는 화면을 연 것으로 (그때부터 오는 알림을 본다)
        vm.onResumed()
        vm.onSettingsOpened()
        assertEquals(SiteAlertStatus.ASKED, (vm.alert.value as SiteAlertState.Ready).alert.status)
        vm.onResumed()
        assertEquals(SiteAlertStatus.MUTED, (vm.alert.value as SiteAlertState.Ready).alert.status)
        vm.allow()
        assertEquals(SiteAlertStatus.ALLOWED, (vm.alert.value as SiteAlertState.Ready).alert.status)
    }

    @Test
    fun unknownSiteIsMissing() = runTest(dispatcher) {
        val vm = SiteAlertViewModel("https://gone.example", repo, notifier, SavedStateHandle())
        backgroundScope.launch { vm.alert.collect {} }
        assertEquals(SiteAlertState.Missing, vm.alert.value)
    }

    @Test
    fun eachNotificationOpensSettingsOnceEvenAfterRestore() {
        val saved = SavedStateHandle()
        val vm = SiteAlertViewModel(SITE, repo, notifier, saved)
        assertEquals(false, vm.shouldAutoOpen(null))
        assertEquals(true, vm.shouldAutoOpen("n1"))
        vm.onSettingsOpened("n1")
        assertEquals(false, vm.shouldAutoOpen("n1"))
        // 화면이 다시 만들어져도 같은 알림으로는 다시 열지 않는다
        assertEquals(false, SiteAlertViewModel(SITE, repo, notifier, saved).shouldAutoOpen("n1"))
        // 새 알림("아직 켜져 있어요")은 다시 연다
        assertEquals(true, vm.shouldAutoOpen("n2"))
    }

    private fun alert(status: SiteAlertStatus) = SiteAlert(SITE, "com.android.chrome", "web:$SITE;1", status, "2026-11-23", 7)

    private class FakeSites : SiteAlertRepository {
        val sites = MutableStateFlow<Map<String, SiteAlert>>(emptyMap())
        override val alerts: Flow<List<SiteAlert>> = sites.map { it.values.toList() }
        override fun alert(site: String): Flow<SiteAlert?> = sites.map { it[site] }
        override suspend fun record(browserPackage: String, channelId: String, text: String, postedAt: Long, canNotify: Boolean): SiteNotice? = null
        override suspend fun prune() = Unit
        override suspend fun markMuted(site: String) = set(site, SiteAlertStatus.MUTED)
        override suspend fun allow(site: String) = set(site, SiteAlertStatus.ALLOWED)
        private fun set(site: String, status: SiteAlertStatus) {
            val a = sites.value[site] ?: return
            sites.value = sites.value + (site to a.copy(status = status))
        }
    }

    private class RecordingNotifier : Notifier {
        val cancelled = mutableListOf<String>()
        override fun canNotify() = true
        override fun notifyAlert(alert: SymptomAlert) = Unit
        override fun cancelAlert(alertId: Long) = Unit
        override fun notifyDecision(alert: SymptomAlert, decision: FamilyDecision, guardianName: String?) = Unit
        override fun notifyLinkRequested(linkId: String, guardianName: String?) = Unit
        override fun notifyLinkActivated(linkId: String, seniorName: String?) = Unit
        override fun notifyLinkRevoked(linkId: String, guardianName: String?, seniorName: String?) = Unit
        override fun cancelLink(linkId: String) = Unit
        override fun notifyContactLost(linkId: String, seniorName: String?) = Unit
        override fun notifyGuardianAlert(message: GuardianAlertMessage) = Unit
        override fun notifyAlertResolved(alertId: String, outcome: String?, seniorName: String?, appLabel: String?) = Unit
        override fun cancelGuardianAlert(alertId: String) = Unit
        override fun notifySiteAds(alert: SiteAlert, stillOn: Boolean) = Unit
        override fun cancelSite(site: String) {
            cancelled += site
        }
    }

    private companion object {
        const val SITE = "https://ads.example.com"
    }
}
