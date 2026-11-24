package io.github.inq_o.phonedoctor.feature.seniorhome.impl

import io.github.inq_o.phonedoctor.core.data.FamilyRepository
import io.github.inq_o.phonedoctor.core.model.AppIdentity
import io.github.inq_o.phonedoctor.core.model.DeviceRole
import io.github.inq_o.phonedoctor.core.model.FamilyLink
import io.github.inq_o.phonedoctor.core.model.PairingCode
import io.github.inq_o.phonedoctor.core.network.ApiException
import io.github.inq_o.phonedoctor.core.packages.AppDetails
import io.github.inq_o.phonedoctor.core.packages.InstalledApp
import io.github.inq_o.phonedoctor.core.packages.InstalledApps
import io.github.inq_o.phonedoctor.core.packages.StrongMode
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SeniorSettingsTest {
    private val dispatcher = StandardTestDispatcher()
    private val family = SettingsFamily()
    private val strong = SettingsStrongMode()
    private val apps = LabelApps(mapOf("com.ad" to "초고속 클리너"))

    @Test
    fun strongModeIsHiddenUnlessDeviceOwner() = runTest(dispatcher) {
        val vm = SeniorSettingsViewModel(family, strong, apps).also { it.io = dispatcher }
        vm.refreshStrongMode()
        advanceUntilIdle()
        assertNull(vm.uiState.value.strongMode)
    }

    @Test
    fun strongModeListsSuspendedAppsAndTogglesInstallBlock() = runTest(dispatcher) {
        strong.active = true
        strong.suspended += listOf("com.ad", "com.gone")
        val vm = SeniorSettingsViewModel(family, strong, apps).also { it.io = dispatcher }
        vm.refreshStrongMode()
        advanceUntilIdle()
        val ui = vm.uiState.value.strongMode!!
        // 라벨을 못 읽으면 패키지 이름으로
        assertEquals(listOf(SuspendedApp("com.ad", "초고속 클리너"), SuspendedApp("com.gone", "com.gone")), ui.suspended)
        assertFalse(ui.unknownSourcesBlocked)
        vm.setUnknownSourcesBlocked(true)
        vm.unsuspend("com.ad")
        advanceUntilIdle()
        assertEquals(true, vm.uiState.value.strongMode!!.unknownSourcesBlocked)
        assertEquals(listOf("com.gone"), vm.uiState.value.strongMode!!.suspended.map { it.packageName })
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun renameTrimsAndRejectsBadLengths() = runTest(dispatcher) {
        val vm = SeniorSettingsViewModel(family, strong, apps)
        advanceUntilIdle()
        assertNull(vm.uiState.value.displayName)
        vm.rename("   ")
        vm.rename("가".repeat(21))
        advanceUntilIdle()
        assertEquals(0, family.renames)
        vm.rename(" 엄마 ")
        advanceUntilIdle()
        assertEquals("엄마", vm.uiState.value.displayName)
        assertEquals(SettingsResult.NAME_SAVED, vm.uiState.value.result)
    }

    @Test
    fun deleteRunsOnceAndReportsFailures() = runTest(dispatcher) {
        val vm = SeniorSettingsViewModel(family, strong, apps)
        val gate = CompletableDeferred<Unit>().also { family.gate = it }
        vm.deleteAll()
        vm.deleteAll()
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, family.deletes)
        assertEquals(SettingsResult.DELETED, vm.uiState.value.result)

        family.fail = IOException("offline")
        vm.deleteAll()
        advanceUntilIdle()
        assertEquals(SettingsResult.NETWORK, vm.uiState.value.result)
        family.fail = ApiException(503, null, null, null, null, null)
        vm.deleteAll()
        advanceUntilIdle()
        assertEquals(SettingsResult.BUSY, vm.uiState.value.result)
        assertFalse(vm.uiState.value.working)
        vm.consumeResult()
        assertNull(vm.uiState.value.result)
    }

    @Test
    fun releaseBuildHidesFamilySettings() {
        family.available = false
        assertFalse(SeniorSettingsViewModel(family, strong, apps).uiState.value.familyAvailable)
    }

    private class SettingsFamily : FamilyRepository {
        override var available = true
        override val role: Flow<DeviceRole?> = flowOf(DeviceRole.SENIOR)
        override val isGuardianSignedIn = false
        override val displayName = MutableStateFlow<String?>(null)
        override val notifyCaution: Flow<Boolean> = flowOf(false)
        var renames = 0
        var deletes = 0
        var fail: Exception? = null
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun setDisplayName(name: String) {
            renames++
            displayName.value = name
        }

        override suspend fun deleteAllData() {
            gate?.await()
            fail?.let {
                fail = null
                throw it
            }
            deletes++
        }

        override suspend fun signInGuardian(googleIdToken: String) = Unit
        override suspend fun createPairingCode(): PairingCode = error("부르지 않음")
        override suspend fun acceptPairingCode(code: String): FamilyLink = error("부르지 않음")
        override suspend fun links(): List<FamilyLink> = emptyList()
        override suspend fun confirmLink(linkId: String): FamilyLink = error("부르지 않음")
        override suspend fun revokeLink(linkId: String) = Unit
        override suspend fun setNotifyCaution(enabled: Boolean) = Unit
        override suspend fun reauthenticateGuardian(googleIdToken: String) = Unit
        override suspend fun reportAppVersion() = Unit
        override suspend fun resumePendingDeletion() = false
        override suspend fun updatePushToken(token: String) = Unit
    }
}

private class SettingsStrongMode : StrongMode {
    override var active = false
    val suspended = mutableListOf<String>()
    private var blocked = false
    override fun suspend(packageName: String) = suspended.add(packageName)
    override fun unsuspend(packageName: String) = suspended.remove(packageName)
    override fun suspendedPackages(): List<String> = suspended.toList()
    override val unknownSourcesBlocked: Boolean get() = blocked
    override fun setUnknownSourcesBlocked(blocked: Boolean) {
        this.blocked = blocked
    }
}

private class LabelApps(private val labels: Map<String, String>) : InstalledApps {
    override val ownPackageName = "io.github.inq_o.phonedoctor"
    override fun snapshot(): Map<String, InstalledApp> = emptyMap()
    override fun identity(packageName: String): AppIdentity? = null
    override fun label(packageName: String): String? = labels[packageName]
    override fun exportedActivities(packageName: String): Set<String>? = null
    override fun isInstalled(packageName: String): Boolean? = null
    override fun details(packageName: String): AppDetails? = null
}

