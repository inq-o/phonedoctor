package io.github.inq_o.phonedoctor.feature.seniorappdetail.impl

import androidx.lifecycle.SavedStateHandle
import io.github.inq_o.phonedoctor.core.data.AppliedDecision
import io.github.inq_o.phonedoctor.core.data.FamilyAlertSync
import io.github.inq_o.phonedoctor.core.data.FamilySyncResult
import io.github.inq_o.phonedoctor.core.data.SymptomRepository
import io.github.inq_o.phonedoctor.core.model.AlertFamilyState
import io.github.inq_o.phonedoctor.core.model.AlertOutcome
import io.github.inq_o.phonedoctor.core.model.AlertStatus
import io.github.inq_o.phonedoctor.core.model.AppIdentity
import io.github.inq_o.phonedoctor.core.model.DailyAppCount
import io.github.inq_o.phonedoctor.core.model.FamilyDecision
import io.github.inq_o.phonedoctor.core.model.ScanRun
import io.github.inq_o.phonedoctor.core.model.SymptomAlert
import io.github.inq_o.phonedoctor.core.notifications.GuardianAlertMessage
import io.github.inq_o.phonedoctor.core.notifications.Notifier
import io.github.inq_o.phonedoctor.core.packages.AppUninstaller
import io.github.inq_o.phonedoctor.core.packages.UninstallResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppDetailFamilyTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val alert = MutableStateFlow<SymptomAlert?>(null)
    private val sync = FakeSync()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun alert(family: AlertFamilyState?, outcome: AlertOutcome? = null) = SymptomAlert(
        1, AppIdentity("com.ad", "00", 1), "초고속 클리너", if (outcome == null) AlertStatus.OPEN else AlertStatus.RESOLVED, outcome,
        "2026-11-23", 5, 1, 0, 0, "test", 0, 0, family = family,
    )

    private val repository = FakeRepository(alert)
    private val uninstaller = FakeUninstaller()

    private fun vm(saved: SavedStateHandle = SavedStateHandle()) = AppDetailViewModel(1, saved, repository, uninstaller, FakeNotifier(), sync)

    @Test
    fun cancelLostWithTheProcessIsStillDeclinedOnReturn() = runTest(dispatcher) {
        // 삭제 창이 떠 있는 동안 프로세스가 끝나 결과 방송을 못 받음: 저장된 상태로 돌아와 아직 있으면 취소로 본다
        alert.value = alert(AlertFamilyState(FamilyDecision.RECOMMEND_UNINSTALL, "민지", null, closed = false))
        val vm = vm(SavedStateHandle(mapOf("uninstallPhase" to UninstallPhase.REQUESTED.name)))
        backgroundScope.launch { vm.uiState.collect {} }
        vm.onResume()
        advanceUntilIdle()
        assertEquals(listOf(AlertOutcome.DECLINED), repository.outcomes)
        // 처음 화면을 열 때(삭제 요청 전)는 아니다
        val fresh = vm()
        fresh.onResume()
        advanceUntilIdle()
        assertEquals(1, repository.outcomes.size)
    }

    @Test
    fun cancellingWhatFamilyAskedToRemoveIsDeclinedAndStaysOpenable() = runTest(dispatcher) {
        alert.value = alert(AlertFamilyState(FamilyDecision.RECOMMEND_UNINSTALL, "민지", null, closed = false))
        val vm = vm()
        backgroundScope.launch { vm.uiState.collect {} }
        vm.uninstall()
        uninstaller.results.emit(UninstallResult.Cancelled("com.ad"))
        advanceUntilIdle()
        assertEquals(listOf(AlertOutcome.DECLINED), repository.outcomes)
        // 닫혔어도(취소) 화면은 남아 다시 지울 수 있다
        alert.value = alert(AlertFamilyState(FamilyDecision.RECOMMEND_UNINSTALL, "민지", null, closed = false), outcome = AlertOutcome.DECLINED)
        assertTrue(vm.uiState.value is AppDetailUiState.Ready)
        // 연결을 끊어 가족 답이 지워지면 닫힌 요청으로 본다
        alert.value = alert(null, outcome = AlertOutcome.DECLINED)
        assertEquals(AppDetailUiState.Gone, vm.uiState.value)
        alert.value = alert(AlertFamilyState(FamilyDecision.RECOMMEND_UNINSTALL, "민지", null, closed = false), outcome = AlertOutcome.DECLINED)
        // 이미 취소로 닫힌 요청에서 또 취소해도 다시 적지 않는다
        vm.uninstall()
        uninstaller.results.emit(UninstallResult.Cancelled("com.ad"))
        advanceUntilIdle()
        assertEquals(1, repository.outcomes.size)
    }

    @Test
    fun cancellingBeforeFamilyAnswersKeepsTheRequestOpen() = runTest(dispatcher) {
        alert.value = alert(AlertFamilyState(null, null, null, closed = false))
        val vm = vm()
        backgroundScope.launch { vm.uiState.collect {} }
        vm.uninstall()
        uninstaller.results.emit(UninstallResult.Cancelled("com.ad"))
        advanceUntilIdle()
        assertTrue(repository.outcomes.isEmpty())
        // 가족과 연결하지 않은 폰도 그대로
        alert.value = alert(null)
        uninstaller.results.emit(UninstallResult.Cancelled("com.ad"))
        advanceUntilIdle()
        assertTrue(repository.outcomes.isEmpty())
    }

    @Test
    fun waitsForFamilyThenShowsRecommendation() = runTest(dispatcher) {
        alert.value = alert(AlertFamilyState(null, null, null, closed = false))
        val vm = vm()
        backgroundScope.launch { vm.uiState.collect {} }
        assertTrue((vm.uiState.value as AppDetailUiState.Ready).waitingForFamily)
        assertEquals(listOf(1L), sync.refreshed)

        alert.value = alert(AlertFamilyState(FamilyDecision.RECOMMEND_UNINSTALL, "민지", "광고 앱이에요", closed = false))
        val ready = vm.uiState.value as AppDetailUiState.Ready
        assertFalse(ready.waitingForFamily)
        assertTrue(ready.familyRecommends)
    }

    @Test
    fun withoutFamilyOrAfterExpiryItIsTheLocalFlow() = runTest(dispatcher) {
        alert.value = alert(null)
        val vm = vm()
        backgroundScope.launch { vm.uiState.collect {} }
        assertFalse((vm.uiState.value as AppDetailUiState.Ready).waitingForFamily)
        alert.value = alert(AlertFamilyState(null, null, null, closed = true))
        assertFalse((vm.uiState.value as AppDetailUiState.Ready).waitingForFamily)
    }

    @Test
    fun suspendedByStrongModeStaysOpenable() = runTest(dispatcher) {
        // 강력 모드로 멈춘 앱은 닫힌 요청이어도 S2에서 지울 수 있다
        alert.value = alert(AlertFamilyState(FamilyDecision.RECOMMEND_UNINSTALL, "민지", null, closed = false), outcome = AlertOutcome.SUSPENDED)
        val vm = vm()
        backgroundScope.launch { vm.uiState.collect {} }
        assertTrue(vm.uiState.value is AppDetailUiState.Ready)
        alert.value = alert(AlertFamilyState(FamilyDecision.RECOMMEND_UNINSTALL, "민지", null, closed = false), outcome = AlertOutcome.UNINSTALLED)
        assertEquals(AppDetailUiState.Gone, vm.uiState.value)
    }

    @Test
    fun allowedByFamilyClosesTheRequest() = runTest(dispatcher) {
        alert.value = alert(AlertFamilyState(FamilyDecision.ALLOW, "민지", null, closed = false), outcome = AlertOutcome.ALLOWED)
        val vm = vm()
        backgroundScope.launch { vm.uiState.collect {} }
        assertEquals(AppDetailUiState.Allowed("민지"), vm.uiState.value)
    }

    @Test
    fun refreshFailureKeepsTheScreen() = runTest(dispatcher) {
        sync.fail = true
        alert.value = alert(AlertFamilyState(null, null, null, closed = false))
        val vm = vm()
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        assertTrue(vm.uiState.value is AppDetailUiState.Ready)
    }

    private class FakeSync : FamilyAlertSync {
        val refreshed = mutableListOf<Long>()
        var fail = false
        override suspend fun sync() = FamilySyncResult(true, emptyList())
        override suspend fun applyDecision(serverAlertId: String, decision: String, guardianName: String?, messageToSenior: String?): AppliedDecision? = null
        override suspend fun refresh(localId: Long) {
            refreshed += localId
            if (fail) throw java.io.IOException("offline")
        }
    }

    private class FakeRepository(private val alert: Flow<SymptomAlert?>) : SymptomRepository {
        override val openAlerts: Flow<List<SymptomAlert>> = flowOf(emptyList())
        override val latestScan: Flow<ScanRun?> = flowOf(null)
        override val scanning: StateFlow<Boolean> = MutableStateFlow(false)
        override fun alert(id: Long): Flow<SymptomAlert?> = alert.map { it }
        override fun recentScans(limit: Int): Flow<List<ScanRun>> = flowOf(emptyList())
        override fun dailyCounts(days: Long): Flow<List<DailyAppCount>> = flowOf(emptyList())
        val outcomes = mutableListOf<AlertOutcome>()
        override suspend fun recordOutcome(id: Long, outcome: AlertOutcome) {
            outcomes += outcome
        }
        override suspend fun confirmRemoved(id: Long, requested: Boolean): Boolean? = false
    }

    private class FakeUninstaller : AppUninstaller {
        override val results = MutableSharedFlow<UninstallResult>()
        override fun requestUninstall(packageName: String) = Unit
    }

    private class FakeNotifier : Notifier {
        override fun canNotify() = true
        override fun notifyAlert(alert: SymptomAlert) = Unit
        override fun cancelAlert(alertId: Long) = Unit
        override fun notifyDecision(alert: SymptomAlert, decision: FamilyDecision, guardianName: String?) = Unit
        override fun notifyLinkRequested(linkId: String, guardianName: String?) = Unit
        override fun notifyLinkActivated(linkId: String, phone: io.github.inq_o.phonedoctor.core.model.PhoneName) = Unit
        override fun notifyLinkRevoked(linkId: String, guardianName: String?, phone: io.github.inq_o.phonedoctor.core.model.PhoneName?) = Unit
        override fun cancelLink(linkId: String) = Unit
        override fun notifyContactLost(linkId: String, phone: io.github.inq_o.phonedoctor.core.model.PhoneName) = Unit
        override fun notifyGuardianAlert(message: GuardianAlertMessage) = Unit
        override fun notifyAlertResolved(alertId: String, outcome: String?, phone: io.github.inq_o.phonedoctor.core.model.PhoneName, appLabel: String?) = Unit
        override fun cancelGuardianAlert(alertId: String) = Unit
        override fun notifySiteAds(alert: io.github.inq_o.phonedoctor.core.model.SiteAlert, stillOn: Boolean) = Unit
        override fun cancelSite(site: String) = Unit
    }
}
