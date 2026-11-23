package io.github.inq_o.phonedoctor.feature.seniorappdetail.impl

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

    private fun vm() = AppDetailViewModel(1, FakeRepository(alert), FakeUninstaller(), FakeNotifier(), sync)

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
        override suspend fun recordOutcome(id: Long, outcome: AlertOutcome) = Unit
        override suspend fun confirmRemoved(id: Long, requested: Boolean): Boolean? = false
    }

    private class FakeUninstaller : AppUninstaller {
        override val results: SharedFlow<UninstallResult> = MutableSharedFlow()
        override fun requestUninstall(packageName: String) = Unit
    }

    private class FakeNotifier : Notifier {
        override fun canNotify() = true
        override fun notifyAlert(alert: SymptomAlert) = Unit
        override fun cancelAlert(alertId: Long) = Unit
        override fun notifyDecision(alert: SymptomAlert, decision: FamilyDecision, guardianName: String?) = Unit
        override fun notifyLinkRequested(linkId: String, guardianName: String?) = Unit
        override fun notifyLinkActivated(linkId: String, seniorName: String?) = Unit
        override fun notifyLinkRevoked(linkId: String, guardianName: String?, seniorName: String?) = Unit
        override fun cancelLink(linkId: String) = Unit
    }
}
