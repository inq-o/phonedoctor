package io.github.inq_o.phonedoctor.feature.onboarding.impl

import android.content.Intent
import io.github.inq_o.phonedoctor.core.data.FamilyRepository
import io.github.inq_o.phonedoctor.core.data.ScanScheduler
import io.github.inq_o.phonedoctor.core.data.UserSettingsRepository
import io.github.inq_o.phonedoctor.core.model.DeviceRole
import io.github.inq_o.phonedoctor.core.model.FamilyDecision
import io.github.inq_o.phonedoctor.core.model.FamilyLink
import io.github.inq_o.phonedoctor.core.model.PairingCode
import io.github.inq_o.phonedoctor.core.model.SymptomAlert
import io.github.inq_o.phonedoctor.core.notifications.GuardianAlertMessage
import io.github.inq_o.phonedoctor.core.notifications.Notifier
import io.github.inq_o.phonedoctor.core.usage.UsageAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OnboardingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val settings = FakeSettings()
    private val scheduler = FakeScheduler()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(familyAvailable: Boolean) =
        OnboardingViewModel(FakeUsageAccess(), FakeNotifier(), settings, scheduler, FakeFamily(familyAvailable))

    @Test
    fun familyStepOnlyWhenFamilyAvailable() {
        assertEquals(OnboardingStep.FAMILY, vm(familyAvailable = true).uiState.value.steps.last())
        assertTrue(OnboardingStep.FAMILY !in vm(familyAvailable = false).uiState.value.steps)
    }

    @Test
    fun connectFamilyFinishesAndAsksToOpenCode() = runTest(dispatcher) {
        val vm = vm(familyAvailable = true)
        while (vm.uiState.value.step != OnboardingStep.FAMILY) vm.next()
        vm.finish(connectFamily = true)
        vm.finish(connectFamily = true)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.finished)
        assertTrue(vm.uiState.value.connectFamily)
        assertEquals(1, settings.completed)
        assertEquals(1, scheduler.scans)
    }

    @Test
    fun skippingFamilyFinishesWithoutCode() = runTest(dispatcher) {
        val vm = vm(familyAvailable = true)
        repeat(vm.uiState.value.steps.size) { vm.next() }
        advanceUntilIdle()
        assertTrue(vm.uiState.value.finished)
        assertEquals(false, vm.uiState.value.connectFamily)
    }

    @Test
    fun roleChoiceSavesOnceThenAllowsChoosingAgainAfterBack() = runTest(dispatcher) {
        val vm = RoleChoiceViewModel(settings)
        vm.choose(DeviceRole.GUARDIAN)
        vm.choose(DeviceRole.SENIOR)
        advanceUntilIdle()
        assertEquals(DeviceRole.GUARDIAN, vm.chosen.value)
        assertEquals(listOf(DeviceRole.GUARDIAN), settings.roles)

        vm.consumeChosen()
        // 넘어가는 동안 다시 누른 것은 저장하지 않는다
        vm.choose(DeviceRole.SENIOR)
        advanceUntilIdle()
        assertEquals(null, vm.chosen.value)
        assertEquals(listOf(DeviceRole.GUARDIAN), settings.roles)

        vm.onShown()
        vm.choose(DeviceRole.SENIOR)
        advanceUntilIdle()
        assertEquals(DeviceRole.SENIOR, vm.chosen.value)
        assertEquals(listOf(DeviceRole.GUARDIAN, DeviceRole.SENIOR), settings.roles)
    }

    private class FakeSettings : UserSettingsRepository {
        var completed = 0
        val roles = mutableListOf<DeviceRole>()
        override val onboardingDone: Flow<Boolean> = flowOf(false)
        override val chosenRole: Flow<DeviceRole?> = flowOf(null)
        override suspend fun completeOnboarding(disclosureVersion: Int) {
            completed++
        }
        override suspend fun chooseRole(role: DeviceRole) {
            roles += role
        }
        override suspend fun clearChosenRole() = Unit
    }

    private class FakeScheduler : ScanScheduler {
        var scans = 0
        override fun ensurePeriodic() = Unit
        override fun scanNow(trigger: String) {
            scans++
        }
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

    /** 첫 설정은 서버를 부르지 않는다: 부르면 실패 */
    private class FakeFamily(override val available: Boolean) : FamilyRepository {
        override val role: Flow<DeviceRole?> = flowOf(null)
        override suspend fun createPairingCode(): PairingCode = error("첫 설정에서 코드를 받지 않음")
        override val isGuardianSignedIn = false
        override suspend fun signInGuardian(googleIdToken: String) = error("부르지 않음")
        override suspend fun acceptPairingCode(code: String): FamilyLink = error("부르지 않음")
        override suspend fun links(): List<FamilyLink> = error("부르지 않음")
        override suspend fun confirmLink(linkId: String): FamilyLink = error("부르지 않음")
        override suspend fun revokeLink(linkId: String) = error("부르지 않음")
        override suspend fun updatePushToken(token: String) = error("부르지 않음")
        override suspend fun deleteAllData() = error("부르지 않음")
        override suspend fun reportAppVersion() = Unit
        override suspend fun resumePendingDeletion() = false
        override val displayName: Flow<String?> = flowOf(null)
        override suspend fun setDisplayName(name: String) = Unit
        override val notifyCaution: Flow<Boolean> = flowOf(false)
        override suspend fun setNotifyCaution(enabled: Boolean) = Unit
        override suspend fun reauthenticateGuardian(googleIdToken: String) = Unit
    }
}
