package io.github.inq_o.phonedoctor.feature.seniorhome.impl

import android.content.Intent
import io.github.inq_o.phonedoctor.core.data.FamilyRepository
import io.github.inq_o.phonedoctor.core.data.ScanScheduler
import io.github.inq_o.phonedoctor.core.data.SymptomRepository
import io.github.inq_o.phonedoctor.core.model.AlertOutcome
import io.github.inq_o.phonedoctor.core.model.DailyAppCount
import io.github.inq_o.phonedoctor.core.model.DeviceRole
import io.github.inq_o.phonedoctor.core.model.FamilyDecision
import io.github.inq_o.phonedoctor.core.model.FamilyLink
import io.github.inq_o.phonedoctor.core.model.LinkStatus
import io.github.inq_o.phonedoctor.core.model.PairingCode
import io.github.inq_o.phonedoctor.core.model.ScanRun
import io.github.inq_o.phonedoctor.core.model.SymptomAlert
import io.github.inq_o.phonedoctor.core.notifications.GuardianAlertMessage
import io.github.inq_o.phonedoctor.core.notifications.Notifier
import io.github.inq_o.phonedoctor.core.usage.UsageAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Instant

/** 홈의 가족 연결 표시: 지켜보는 가족과 놓친 연결 요청 (push-messages.md "누락 대비") */
class SeniorHomeFamilyTest {
    private val dispatcher = StandardTestDispatcher()
    private val family = FakeFamily()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = SeniorHomeViewModel(FakeSymptoms(), FakeUsageAccess(), FakeNotifier(), FakeScheduler(), family)

    private fun link(id: String, status: LinkStatus, name: String, createdAt: Long) =
        FamilyLink(id, status, "엄마", name, Instant.ofEpochSecond(createdAt), null, null)

    @Test
    fun unavailableBuildNeverCallsServer() = runTest(dispatcher) {
        family.available = false
        val vm = vm()
        vm.refresh()
        advanceUntilIdle()
        assertEquals(0, family.listCalls)
        assertEquals(HomeFamily(available = false), vm.family.value)
    }

    @Test
    fun showsGuardiansAndNewestPendingRequest() = runTest(dispatcher) {
        family.links = listOf(
            link("b", LinkStatus.ACTIVE, "철수", 2),
            link("a", LinkStatus.ACTIVE, "민지", 1),
            link("r", LinkStatus.REVOKED, "영희", 0),
            link("p1", LinkStatus.PENDING, "동생", 3),
            link("p2", LinkStatus.PENDING, "삼촌", 4),
        )
        val vm = vm()
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf("민지", "철수"), vm.family.value.guardians)
        assertEquals("p2", vm.family.value.pending?.linkId)
    }

    @Test
    fun failureKeepsWhatWasShown() = runTest(dispatcher) {
        family.links = listOf(link("a", LinkStatus.ACTIVE, "민지", 1))
        val vm = vm()
        vm.refresh()
        advanceUntilIdle()
        family.error = IOException("offline")
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf("민지"), vm.family.value.guardians)
        assertNull(vm.family.value.pending)
    }

    private class FakeFamily : FamilyRepository {
        override var available = true
        var links = emptyList<FamilyLink>()
        var error: Exception? = null
        var listCalls = 0
        override val role: Flow<DeviceRole?> = flowOf(DeviceRole.SENIOR)
        override suspend fun links(): List<FamilyLink> {
            listCalls++
            error?.let { throw it }
            return links
        }
        override suspend fun createPairingCode(): PairingCode = error("홈에서 부르지 않음")
        override val isGuardianSignedIn = false
        override suspend fun signInGuardian(googleIdToken: String) = error("부르지 않음")
        override suspend fun acceptPairingCode(code: String): FamilyLink = error("홈에서 부르지 않음")
        override suspend fun confirmLink(linkId: String): FamilyLink = error("홈에서 부르지 않음")
        override suspend fun revokeLink(linkId: String) = error("홈에서 부르지 않음")
        override suspend fun updatePushToken(token: String) = Unit
        override suspend fun deleteAllData() = Unit
        override suspend fun reportAppVersion() = Unit
        override suspend fun resumePendingDeletion() = false
        override val displayName: Flow<String?> = flowOf(null)
        override suspend fun setDisplayName(name: String) = Unit
        override val notifyCaution: Flow<Boolean> = flowOf(false)
        override suspend fun setNotifyCaution(enabled: Boolean) = Unit
        override suspend fun reauthenticateGuardian(googleIdToken: String) = Unit
    }

    private class FakeSymptoms : SymptomRepository {
        override val openAlerts: Flow<List<SymptomAlert>> = flowOf(emptyList())
        override val latestScan: Flow<ScanRun?> = flowOf(null)
        override val scanning: StateFlow<Boolean> = MutableStateFlow(false)
        override fun alert(id: Long): Flow<SymptomAlert?> = flowOf(null)
        override fun recentScans(limit: Int): Flow<List<ScanRun>> = flowOf(emptyList())
        override fun dailyCounts(days: Long): Flow<List<DailyAppCount>> = flowOf(emptyList())
        override suspend fun recordOutcome(id: Long, outcome: AlertOutcome) = Unit
        override suspend fun confirmRemoved(id: Long, requested: Boolean): Boolean? = null
    }

    private class FakeScheduler : ScanScheduler {
        override fun ensurePeriodic() = Unit
        override fun scanNow(trigger: String) = Unit
    }

    private class FakeUsageAccess : UsageAccess {
        override fun isGranted() = true
        override fun settingsIntents(): List<Intent> = emptyList()
    }

    private class FakeNotifier : Notifier {
        override fun canNotify() = true
        override fun notifyAlert(alert: SymptomAlert) = Unit
        override fun cancelAlert(alertId: Long) = Unit
        override fun notifyDecision(alert: SymptomAlert, decision: FamilyDecision, guardianName: String?) = Unit
        override fun notifyLinkRequested(linkId: String, guardianName: String?) = Unit
        override fun notifyLinkRevoked(linkId: String, guardianName: String?, seniorName: String?) = Unit
        override fun notifyLinkActivated(linkId: String, seniorName: String?) = Unit
        override fun cancelLink(linkId: String) = Unit
        override fun notifyContactLost(linkId: String, seniorName: String?) = Unit
        override fun notifyGuardianAlert(message: GuardianAlertMessage) = Unit
        override fun notifyAlertResolved(alertId: String, outcome: String?, seniorName: String?, appLabel: String?) = Unit
        override fun cancelGuardianAlert(alertId: String) = Unit
    }
}
