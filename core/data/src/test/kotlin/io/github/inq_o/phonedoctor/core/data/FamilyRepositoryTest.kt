package io.github.inq_o.phonedoctor.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.inq_o.phonedoctor.core.datastore.UserPreferences
import io.github.inq_o.phonedoctor.core.model.DeviceRole
import io.github.inq_o.phonedoctor.core.model.LinkStatus
import io.github.inq_o.phonedoctor.core.network.ApiException
import io.github.inq_o.phonedoctor.core.network.PhoneDoctorNetworkDataSource
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlert
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlertCreate
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlertList
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlertStatus
import io.github.inq_o.phonedoctor.core.network.model.NetworkDecisionCreate
import io.github.inq_o.phonedoctor.core.network.model.NetworkDevice
import io.github.inq_o.phonedoctor.core.network.model.NetworkDeviceRegistration
import io.github.inq_o.phonedoctor.core.network.model.NetworkDeviceRole
import io.github.inq_o.phonedoctor.core.network.model.NetworkDeviceStatus
import io.github.inq_o.phonedoctor.core.network.model.NetworkDeviceSummary
import io.github.inq_o.phonedoctor.core.network.model.NetworkDeviceUpdate
import io.github.inq_o.phonedoctor.core.network.model.NetworkLink
import io.github.inq_o.phonedoctor.core.network.model.NetworkLinkStatus
import io.github.inq_o.phonedoctor.core.network.model.NetworkOutcomeCreate
import io.github.inq_o.phonedoctor.core.network.model.NetworkPairingCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Instant

class FamilyRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val network = FakeNetwork()
    private val account = FakeAccount()

    private fun TestScope.repo(available: Boolean = true): DefaultFamilyRepository {
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))) {
            tmp.newFile("prefs.preferences_pb").also { it.delete() }
        }
        return DefaultFamilyRepository(available, network, UserPreferences(store), account, { "fcm-1" }, FakeDeviceInfo)
    }

    @Test
    fun `연결 시작 전에는 서버를 부르지 않는다`() = runTest {
        val repo = repo()
        assertEquals(emptyList<Any>(), repo.links())
        repo.updatePushToken("fcm-2")
        assertNull(repo.role.first())
        assertEquals(emptyList<String>(), network.calls)
        assertFalse(account.isSignedIn)
    }

    @Test
    fun `시니어는 첫 코드 요청 때 익명 로그인하고 한 번만 등록한다`() = runTest {
        val repo = repo()
        val code = repo.createPairingCode()
        repo.createPairingCode()

        assertEquals("482913", code.code)
        assertEquals(Instant.parse("2026-11-23T01:10:00Z"), code.expiresAt)
        assertTrue(account.isSignedIn && account.isAnonymous)
        assertEquals(listOf("registerDevice", "createPairingCode", "createPairingCode"), network.calls)
        val body = network.registrations.single()
        assertEquals(NetworkDeviceRole.PROTECTED, body.role)
        assertEquals("fcm-1", body.fcmToken)
        assertEquals(DeviceRole.SENIOR, repo.role.first())
        // 요청마다 새 Idempotency-Key
        assertEquals(network.keys.size, network.keys.toSet().size)
    }

    @Test
    fun `보호자는 Google 로그인 전이면 등록하지 않는다`() = runTest {
        val repo = repo()
        account.signedIn = true
        account.anonymous = true
        try {
            repo.acceptPairingCode("482913")
            fail()
        } catch (_: GuardianSignInRequiredException) {
        }
        assertEquals(emptyList<String>(), network.calls)
    }

    @Test
    fun `보호자는 Google 로그인 뒤 등록하고 코드를 넣는다`() = runTest {
        val repo = repo()
        account.signedIn = true
        val link = repo.acceptPairingCode("482913")
        assertEquals(LinkStatus.PENDING, link.status)
        assertEquals("엄마", link.seniorName)
        assertEquals(NetworkDeviceRole.GUARDIAN, network.registrations.single().role)
        assertEquals(listOf("registerDevice", "acceptPairingCode"), network.calls)
    }

    @Test
    fun `다른 역할로 등록한 기기는 거절한다`() = runTest {
        val repo = repo()
        repo.createPairingCode()
        network.calls.clear()
        try {
            repo.acceptPairingCode("482913")
            fail()
        } catch (e: RoleMismatchException) {
            assertEquals(DeviceRole.SENIOR, e.registered)
        }
        assertEquals(emptyList<String>(), network.calls)
    }

    @Test
    fun `꺼진 빌드는 아무것도 보내지 않는다`() = runTest {
        val repo = repo(available = false)
        for (call in listOf<suspend () -> Unit>({ repo.createPairingCode() }, { repo.links() }, { repo.deleteAllData() })) {
            try {
                call()
                fail()
            } catch (_: IllegalStateException) {
            }
        }
        repo.updatePushToken("fcm-2")
        assertEquals(emptyList<String>(), network.calls)
        assertFalse(account.isSignedIn)
    }

    @Test
    fun `토큰 갱신은 등록한 기기에만 보낸다`() = runTest {
        val repo = repo()
        repo.createPairingCode()
        repo.updatePushToken("fcm-2")
        assertEquals("fcm-2", network.updates.single().second.fcmToken)
        assertEquals("dev-1", network.updates.single().first)
    }

    @Test
    fun `모든 데이터 지우기는 서버 기기와 Firebase 사용자를 지운다`() = runTest {
        val repo = repo()
        repo.createPairingCode()
        repo.deleteAllData()
        assertEquals("deleteDevice", network.calls.last())
        assertEquals(listOf("dev-1"), network.deleted)
        assertNull(repo.role.first())
        assertFalse(account.isSignedIn)
        assertEquals(1, account.deleted)
    }

    @Test
    fun `서버에 이미 없는 기기(404)도 지운 것으로 본다`() = runTest {
        val repo = repo()
        repo.createPairingCode()
        network.deleteError = ApiException(404, "not-found", null, null, null, null)
        repo.deleteAllData()
        assertNull(repo.role.first())
        assertEquals(1, account.deleted)
    }

    @Test
    fun `서버 삭제가 실패하면 기기 정보와 Firebase 사용자를 남긴다`() = runTest {
        val repo = repo()
        repo.createPairingCode()
        network.deleteError = IOException("망 끊김")
        try {
            repo.deleteAllData()
            fail()
        } catch (_: IOException) {
        }
        assertEquals(DeviceRole.SENIOR, repo.role.first())
        assertTrue(account.isSignedIn)
        assertEquals(0, account.deleted)
    }

    @Test
    fun `Firebase 삭제가 실패해 다시 지울 때는 서버를 다시 부르지 않는다`() = runTest {
        val repo = repo()
        repo.createPairingCode()
        account.deleteError = IllegalStateException("다시 로그인 필요")
        try {
            repo.deleteAllData()
            fail()
        } catch (_: IllegalStateException) {
        }
        assertNull(repo.role.first())
        account.deleteError = null
        repo.deleteAllData()
        assertEquals(listOf("dev-1"), network.deleted)
        assertEquals(1, account.deleted)
    }

    @Test
    fun `연결 상태와 시각을 바꾼다`() = runTest {
        val repo = repo()
        repo.createPairingCode()
        network.linkStatus = NetworkLinkStatus.ACTIVE
        val link = repo.confirmLink("link-1")
        assertEquals(LinkStatus.ACTIVE, link.status)
        assertEquals(Instant.parse("2026-11-23T00:00:00Z"), link.createdAt)
        // 오프셋이 붙은 시각도 받는다
        assertEquals(Instant.parse("2026-11-23T00:05:00Z"), link.activatedAt)
        assertEquals("민지", link.guardianName)
    }

    @Test
    fun `등록 전 확인·끊기는 거절한다`() = runTest {
        val repo = repo()
        for (call in listOf<suspend () -> Unit>({ repo.confirmLink("link-1") }, { repo.revokeLink("link-1") })) {
            try {
                call()
                fail()
            } catch (_: IllegalStateException) {
            }
        }
        assertEquals(emptyList<String>(), network.calls)
    }
}

private object FakeDeviceInfo : DeviceInfo {
    override val appVersionCode = 1
    override val sdkInt = 31
    override val manufacturer = "samsung"
    override val locale = "ko-KR"
}

private class FakeAccount : FamilyAccount {
    var signedIn = false
    var anonymous = false
    var deleted = 0
    var deleteError: Exception? = null
    override val isSignedIn get() = signedIn
    override val isAnonymous get() = signedIn && anonymous

    override suspend fun signInAnonymously() {
        signedIn = true
        anonymous = true
    }

    override suspend fun deleteUser() {
        deleteError?.let { throw it }
        if (signedIn) deleted++
        signedIn = false
    }
}

private class FakeNetwork : PhoneDoctorNetworkDataSource {
    val calls = mutableListOf<String>()
    val keys = mutableListOf<String>()
    val registrations = mutableListOf<NetworkDeviceRegistration>()
    val updates = mutableListOf<Pair<String, NetworkDeviceUpdate>>()
    val deleted = mutableListOf<String>()
    var deleteError: Exception? = null
    var linkStatus = NetworkLinkStatus.PENDING

    private fun link() = NetworkLink(
        linkId = "link-1",
        status = linkStatus,
        protectedDevice = NetworkDeviceSummary("dev-1", "엄마"),
        guardianDevice = NetworkDeviceSummary("dev-2", "민지"),
        createdAt = "2026-11-23T00:00:00Z",
        activatedAt = if (linkStatus == NetworkLinkStatus.ACTIVE) "2026-11-23T09:05:00+09:00" else null,
    )

    override suspend fun registerDevice(key: String, body: NetworkDeviceRegistration): NetworkDevice {
        calls += "registerDevice"; keys += key; registrations += body
        return NetworkDevice("dev-1", body.role, "엄마", createdAt = "2026-11-23T00:00:00Z")
    }

    override suspend fun updateDevice(deviceId: String, body: NetworkDeviceUpdate): NetworkDevice {
        calls += "updateDevice"; updates += deviceId to body
        return NetworkDevice(deviceId, NetworkDeviceRole.PROTECTED, "엄마", createdAt = "2026-11-23T00:00:00Z")
    }

    override suspend fun deleteDevice(deviceId: String) {
        calls += "deleteDevice"
        deleteError?.let { throw it }
        deleted += deviceId
    }

    override suspend fun createPairingCode(key: String): NetworkPairingCode {
        calls += "createPairingCode"; keys += key
        return NetworkPairingCode("482913", "phonedoctor://pair?code=482913", "2026-11-23T01:10:00Z")
    }

    override suspend fun acceptPairingCode(key: String, code: String): NetworkLink {
        calls += "acceptPairingCode"; keys += key
        return link()
    }

    override suspend fun listLinks(status: NetworkLinkStatus?): List<NetworkLink> {
        calls += "listLinks"
        return listOf(link())
    }

    override suspend fun confirmLink(key: String, linkId: String): NetworkLink {
        calls += "confirmLink"; keys += key
        return link()
    }

    override suspend fun revokeLink(linkId: String) {
        calls += "revokeLink"
    }

    override suspend fun reportStatus(deviceId: String, body: NetworkDeviceStatus) = TODO()
    override suspend fun createAlert(key: String, body: NetworkAlertCreate): NetworkAlert = TODO()
    override suspend fun listAlerts(status: NetworkAlertStatus?, updatedSince: String?, pageToken: String?, pageSize: Int?): NetworkAlertList = TODO()
    override suspend fun getAlert(alertId: String): NetworkAlert = TODO()
    override suspend fun decideAlert(key: String, alertId: String, body: NetworkDecisionCreate): NetworkAlert = TODO()
    override suspend fun reportOutcome(key: String, alertId: String, body: NetworkOutcomeCreate): NetworkAlert = TODO()
}
