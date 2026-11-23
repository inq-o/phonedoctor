package io.github.inq_o.phonedoctor.core.data

import io.github.inq_o.phonedoctor.core.datastore.RegisteredDevice
import io.github.inq_o.phonedoctor.core.datastore.UserPreferences
import io.github.inq_o.phonedoctor.core.model.DeviceRole
import io.github.inq_o.phonedoctor.core.model.FamilyLink
import io.github.inq_o.phonedoctor.core.model.LinkStatus
import io.github.inq_o.phonedoctor.core.model.PairingCode
import io.github.inq_o.phonedoctor.core.model.ProtectionFlags
import io.github.inq_o.phonedoctor.core.network.ApiException
import io.github.inq_o.phonedoctor.core.network.PhoneDoctorNetworkDataSource
import io.github.inq_o.phonedoctor.core.network.model.NetworkDeviceRegistration
import io.github.inq_o.phonedoctor.core.network.model.NetworkDeviceRole
import io.github.inq_o.phonedoctor.core.network.model.NetworkDeviceUpdate
import io.github.inq_o.phonedoctor.core.network.model.NetworkLink
import io.github.inq_o.phonedoctor.core.network.model.NetworkLinkStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Qualifier

/**
 * 가족 연결 (US3, ux-flows F2·F4). 서버와 처음 통신하는 때는 연결을 시작할 때다:
 * 시니어는 [createPairingCode], 보호자는 [acceptPairingCode]를 처음 부를 때 로그인하고 기기를 등록한다. 그 전에는 아무것도 보내지 않는다.
 * 실패는 서버가 답한 오류면 ApiException, 망 문제면 IOException으로 던진다. IOException 뒤 같은 동작을 다시 부르면 같은 Idempotency-Key로 보낸다
 */
interface FamilyRepository {
    /** 이 빌드에서 가족 연결을 쓸 수 있는지. false면 화면에서 진입점을 숨긴다 (출시 빌드는 개인정보처리방침을 고치기 전까지 false) */
    val available: Boolean

    /** 서버에 등록한 역할. 연결을 시작하기 전이면 null */
    val role: Flow<DeviceRole?>

    /** 보호자로 Google 로그인돼 있는지. 아니면 O-G1부터 */
    val isGuardianSignedIn: Boolean

    /**
     * 보호자: Credential Manager로 받은 Google ID 토큰으로 Firebase에 로그인한다 (O-G1). 서버에는 아직 아무것도 보내지 않는다(등록은 코드를 넣을 때).
     * 이미 시니어로 등록한 기기면 RoleMismatchException
     */
    suspend fun signInGuardian(googleIdToken: String)

    /** 시니어: 연결 코드를 새로 받는다 (O-S4, 10분) */
    suspend fun createPairingCode(): PairingCode

    /** 보호자: 시니어 기기의 코드를 넣는다 (O-G2). Google 로그인 뒤에만 */
    suspend fun acceptPairingCode(code: String): FamilyLink

    /** 내 연결 목록. 연결을 시작하기 전이면 서버를 부르지 않고 빈 목록 */
    suspend fun links(): List<FamilyLink>

    /** 시니어: 보호자의 연결 요청을 받아들인다 (S3) */
    suspend fun confirmLink(linkId: String): FamilyLink

    /** 연결 끊기 (F4). 시니어·보호자 모두 */
    suspend fun revokeLink(linkId: String)

    /** 가족에게 보이는 이 폰의 이름. 정하지 않았으면 null (서버 기본: 시니어 "부모님", 보호자 Google 이름) */
    val displayName: Flow<String?>

    /** 이름을 바꾼다 (1~20자). 등록한 폰이면 서버에도 바로 보낸다. 등록 전이면 등록할 때 함께 보낸다 */
    suspend fun setDisplayName(name: String)

    /** (보호자) "주의 단계도 받기": 설치 시점 "주의" 판정도 알림 (FR-028) */
    val notifyCaution: Flow<Boolean>

    suspend fun setNotifyCaution(enabled: Boolean)

    /** (보호자) 모든 데이터 지우기가 [RecentLoginRequiredException]으로 멈췄을 때 Google로 다시 로그인한다 */
    suspend fun reauthenticateGuardian(googleIdToken: String)

    /** 앱이 업데이트됐으면 서버의 기기 정보(appVersionCode)를 고친다. 등록 전이면 아무것도 안 한다 */
    suspend fun reportAppVersion()

    /** FCM 토큰이 바뀌었을 때. 등록 전이면 아무것도 안 한다(다음 등록 때 새 토큰을 보냄) */
    suspend fun updatePushToken(token: String)

    /** 모든 데이터 지우기 (S7, 개인정보처리방침 3·6절): 서버의 기기와 연결·요청을 지우고 FCM 토큰과 Firebase 사용자도 지운다 */
    suspend fun deleteAllData()
}

/** 보호자는 Google 로그인 사용자만 서버에 등록할 수 있다 (openapi POST /devices). 화면은 O-G1로 보낸다 */
class GuardianSignInRequiredException : IllegalStateException("보호자는 Google 로그인이 필요함")

/**
 * 이 기기가 서버에 등록돼 있지 않음: 연결을 시작하기 전이거나, 서버가 기기를 지워서(403 device-not-registered) 잊은 뒤.
 * 어느 쪽이든 서버에 이 기기의 연결은 없다
 */
class DeviceNotRegisteredException : IllegalStateException("서버에 등록된 기기 없음")

/** Google 사용자를 지우려면 최근에 다시 로그인해야 한다 (Firebase). 화면이 Google 로그인을 한 번 더 받은 뒤 다시 지운다 */
class RecentLoginRequiredException : IllegalStateException("다시 로그인 필요")

/** 표시 이름은 1~20자 (서버 Devices.kt NAME_MAX) */
const val DISPLAY_NAME_MAX = 20

/** 이미 다른 역할로 등록한 기기. 역할을 바꾸려면 모든 데이터 지우기 뒤 다시 */
class RoleMismatchException(val registered: DeviceRole) : IllegalStateException("이미 ${registered.name} 역할로 등록됨")

/** Firebase 로그인 (시니어 익명, 보호자 Google). 시험에서는 가짜로 바꾼다 */
interface FamilyAccount {
    val isSignedIn: Boolean
    val isAnonymous: Boolean
    suspend fun signInAnonymously()

    /** Google ID 토큰으로 로그인 (보호자). 익명 사용자였으면 그 사용자는 버리고 Google 사용자로 바뀐다 */
    suspend fun signInWithGoogle(idToken: String)

    /** 지금 사용자를 같은 Google 계정으로 다시 확인한다 (사용자 삭제 전) */
    suspend fun reauthenticateWithGoogle(idToken: String)
    fun signOut()

    /** 지금 사용자를 지우고 로그아웃. 로그인 전이면 아무것도 안 한다 */
    suspend fun deleteUser()
}

/** 서버가 푸시를 보낼 이 기기의 FCM 토큰 */
interface PushTokens {
    suspend fun token(): String

    /** 자동 발급·교체. 등록 전에는 꺼 두고(연결 전 전송 없음) 등록 뒤 켠다. 켜면 SDK가 토큰 교체를 onNewToken으로 알린다 */
    fun setAutoRefresh(enabled: Boolean)

    /** 이 기기의 FCM 등록을 지운다 */
    suspend fun delete()
}

/** 새 FCM 토큰을 서버에 보내는 일을 망이 될 때까지 재시도하며 맡긴다 (sync:work) */
fun interface PushTokenSync {
    fun schedule(token: String)
}

/** 등록 때 보내는 기기 정보 (data-model Device) */
interface DeviceInfo {
    val appVersionCode: Int
    val sdkInt: Int
    val manufacturer: String
    val locale: String
}

/** 가족 연결을 쓸 수 있는지 (core:network FamilyFeature.isAvailable) */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FamilyEnabled

internal class DefaultFamilyRepository @Inject constructor(
    @FamilyEnabled override val available: Boolean,
    private val network: PhoneDoctorNetworkDataSource,
    private val preferences: UserPreferences,
    private val account: FamilyAccount,
    private val pushTokens: PushTokens,
    private val deviceInfo: DeviceInfo,
    private val linkEvents: FamilyLinkEvents,
) : FamilyRepository {
    /**
     * 서버를 바꾸는 동작은 하나씩. 등록이 겹치거나, 코드 받기를 두 번 눌러 뒤늦게 온 응답(서버가 이미 폐기한 코드)이 화면에 남거나,
     * 지우는 중에 다른 요청이 끼지 않게
     */
    private val mutex = Mutex()

    /** 응답을 못 받은(IOException) 동작의 Idempotency-Key. 같은 동작을 다시 부르면 이 키로 보내 서버가 한 번만 처리한다 */
    private val pendingKeys = mutableMapOf<String, String>()

    override val role: Flow<DeviceRole?> = preferences.registeredDevice.map { it?.let { d -> DeviceRole.valueOf(d.role) } }

    // 꺼진 빌드는 Firebase 설정이 없을 수 있어 로그인 상태를 읽지 않는다
    override val isGuardianSignedIn: Boolean get() = available && account.isSignedIn && !account.isAnonymous

    override suspend fun signInGuardian(googleIdToken: String) = serialized {
        // 시니어로 등록한 기기의 익명 사용자를 Google 사용자로 바꾸면 서버의 시니어 기기에 다시 닿을 수 없다
        registered()?.let { if (it.role != DeviceRole.GUARDIAN.name) throw RoleMismatchException(DeviceRole.valueOf(it.role)) }
        account.signInWithGoogle(googleIdToken)
    }

    override suspend fun createPairingCode(): PairingCode = serialized {
        // 새 키로 보낸다: 코드는 10분이면 만료라, 예전 키의 저장된 응답(만료된 코드)을 되받으면 안 된다. 새 코드를 받으면 예전 코드는 서버가 폐기
        val code = withDevice(DeviceRole.SENIOR) { network.createPairingCode(newKey()) }
        PairingCode(code.code, code.qrPayload, code.expiresAt.toInstant())
    }

    override suspend fun acceptPairingCode(code: String): FamilyLink = serialized {
        // 응답을 못 받고 다시 넣으면 같은 키로: 새 키면 서버가 이미 쓴 코드(410)로 답해 연결이 생겼는데도 실패로 보인다
        withDevice(DeviceRole.GUARDIAN) { withKey("accept:$code") { network.acceptPairingCode(it, code) } }.toModel()
    }

    override suspend fun links(): List<FamilyLink> = serialized {
        registered() ?: return@serialized emptyList()
        try {
            network.listLinks().map { it.toModel() }
        } catch (e: ApiException) {
            if (!e.isDeviceGone()) throw e
            forgetDevice()
            emptyList()
        }
    }

    override suspend fun confirmLink(linkId: String): FamilyLink = serialized {
        requireRegistered()
        forgettingGoneDevice { withKey("confirm:$linkId") { network.confirmLink(it, linkId) } }.toModel()
            .also { linkEvents.onLinkConfirmed() }
    }

    override suspend fun revokeLink(linkId: String) = serialized {
        requireRegistered()
        forgettingGoneDevice { network.revokeLink(linkId) }
        linkEvents.onLinkRevoked()
    }

    override val displayName: Flow<String?> = preferences.displayName

    override suspend fun setDisplayName(name: String) {
        val trimmed = name.trim()
        require(trimmed.length in 1..DISPLAY_NAME_MAX) { "이름은 1~${DISPLAY_NAME_MAX}자" }
        serialized {
            // 서버에 먼저: 실패하면 화면이 그대로 두고 다시 하게 (등록 전이면 저장만)
            registered()?.let { forgettingGoneDevice { network.updateDevice(it.deviceId, NetworkDeviceUpdate(displayName = trimmed)) } }
            preferences.setDisplayName(trimmed)
        }
    }

    override val notifyCaution: Flow<Boolean> = preferences.notifyCaution

    override suspend fun setNotifyCaution(enabled: Boolean) = serialized {
        registered()?.let { forgettingGoneDevice { network.updateDevice(it.deviceId, NetworkDeviceUpdate(notifyCaution = enabled)) } }
        preferences.setNotifyCaution(enabled)
    }

    override suspend fun reauthenticateGuardian(googleIdToken: String) = serialized {
        account.reauthenticateWithGoogle(googleIdToken)
    }

    override suspend fun reportAppVersion() {
        if (!available) return
        mutex.withLock {
            val device = registered() ?: return
            val version = deviceInfo.appVersionCode
            if (preferences.reportedAppVersion.first() == version) return
            try {
                network.updateDevice(device.deviceId, NetworkDeviceUpdate(appVersionCode = version))
                preferences.setReportedAppVersion(version)
            } catch (e: ApiException) {
                if (e.status == 404) forgetDevice() else throw e
            }
        }
    }

    override suspend fun updatePushToken(token: String) {
        if (!available) return
        mutex.withLock {
            val device = registered() ?: return
            try {
                network.updateDevice(device.deviceId, NetworkDeviceUpdate(fcmToken = token))
            } catch (e: ApiException) {
                // 서버가 지운 기기 (오래 안 써서 180일 정리). 다음 연결 때 새로 등록한다
                if (e.status == 404) forgetDevice() else throw e
            }
        }
    }

    override suspend fun deleteAllData() = serialized {
        var device = registered()
        if (device == null && account.isSignedIn) {
            // 등록 요청을 보냈는데 응답을 못 받았을 수 있다. 같은 사용자로 다시 등록하면(서버가 갱신으로 처리) ID를 알아 지울 수 있다.
            // Firebase 사용자를 먼저 지우면 서버의 기기에 다시 닿을 수 없다
            preferences.pendingRegistrationRole.first()?.let { device = register(DeviceRole.valueOf(it)) }
        }
        device?.let {
            network.deleteDevice(it.deviceId) // 이미 없어도 서버는 204
            // 서버에서 지운 뒤 바로 비운다. 아래 Firebase 삭제가 실패해 다시 눌러도 서버 삭제를 되풀이하지 않게
            forgetDevice()
        } ?: linkEvents.onDataDeleted()
        preferences.setPendingRegistrationRole(null)
        preferences.setReportedAppVersion(null)
        pushTokens.setAutoRefresh(false)
        pushTokens.delete()
        account.deleteUser()
    }

    private suspend fun <T> serialized(block: suspend () -> T): T {
        check(available) { "이 빌드는 가족 연결이 꺼져 있음" }
        return mutex.withLock { block() }
    }

    /** [role]로 등록한 뒤 [block]. 서버가 기기를 지웠으면(403 device-not-registered) 다시 등록하고 한 번 더 */
    private suspend fun <T> withDevice(role: DeviceRole, block: suspend () -> T): T {
        ensureRegistered(role)
        return try {
            block()
        } catch (e: ApiException) {
            if (!e.isDeviceGone()) throw e
            forgetDevice()
            ensureRegistered(role)
            block()
        }
    }

    /** 서버가 기기를 지웠으면 로컬 등록도 비우고 오류는 그대로 (연결도 이미 없다) */
    private suspend fun <T> forgettingGoneDevice(block: suspend () -> T): T = try {
        block()
    } catch (e: ApiException) {
        if (!e.isDeviceGone()) throw e
        forgetDevice()
        throw DeviceNotRegisteredException()
    }

    private suspend fun <T> withKey(op: String, block: suspend (String) -> T): T {
        val key = pendingKeys.getOrPut(op) { newKey() }
        return try {
            block(key).also { pendingKeys.remove(op) }
        } catch (e: IOException) {
            throw e // 서버가 처리했는지 모름 → 다시 부를 때 같은 키
        } catch (e: Exception) {
            pendingKeys.remove(op)
            throw e
        }
    }

    private suspend fun ensureRegistered(role: DeviceRole) {
        registered()?.let { device ->
            val registeredRole = DeviceRole.valueOf(device.role)
            if (registeredRole != role) throw RoleMismatchException(registeredRole)
            return
        }
        when (role) {
            DeviceRole.SENIOR -> if (!account.isSignedIn || !account.isAnonymous) {
                // 시니어는 익명만 (openapi 인증). 보호자로 고르다 Google 로그인한 채 돌아왔으면 그 계정에 시니어 기기를 묶지 않는다
                if (account.isSignedIn) account.signOut()
                account.signInAnonymously()
            }
            DeviceRole.GUARDIAN -> if (!account.isSignedIn || account.isAnonymous) throw GuardianSignInRequiredException()
        }
        register(role)
    }

    /** 서버는 같은 Firebase 사용자의 재등록을 갱신으로 처리한다. 응답을 못 받고 다시 불러도 기기가 둘 생기지 않는다 */
    private suspend fun register(role: DeviceRole): RegisteredDevice {
        // 보냈다는 표시를 먼저 남긴다: 응답을 못 받은 채 앱이 꺼져도 모든 데이터 지우기가 서버 기기를 찾아 지울 수 있게
        preferences.setPendingRegistrationRole(role.name)
        val device = try {
            withKey("register:$role") {
                network.registerDevice(
                    it,
                    NetworkDeviceRegistration(
                        role = role.toNetwork(),
                        fcmToken = pushTokens.token(),
                        // 정해 둔 이름이 있으면 처음부터 그 이름으로 (없으면 서버 기본)
                        displayName = preferences.displayName.first(),
                        appVersionCode = deviceInfo.appVersionCode,
                        sdkInt = deviceInfo.sdkInt,
                        manufacturer = deviceInfo.manufacturer,
                        locale = deviceInfo.locale,
                    ),
                )
            }
        } catch (e: ApiException) {
            preferences.setPendingRegistrationRole(null) // 서버가 거절함 = 만들어지지 않음
            throw e
        }
        val registered = RegisteredDevice(device.deviceId, role.name)
        preferences.setRegisteredDevice(registered)
        preferences.setReportedAppVersion(deviceInfo.appVersionCode)
        // 등록 전에 켜 둔 "주의 단계도 받기" (등록 본문에는 없는 값). 실패해도 등록은 된 것이라 다음에 설정에서 다시
        if (role == DeviceRole.GUARDIAN && preferences.notifyCaution.first()) {
            runCatching { network.updateDevice(device.deviceId, NetworkDeviceUpdate(notifyCaution = true)) }
        }
        preferences.setPendingRegistrationRole(null)
        pushTokens.setAutoRefresh(true)
        return registered
    }

    /** 서버에 이 기기가 없다 (지웠거나 서버가 정리함). 연결·요청도 서버에 없으므로 요청 사본도 비운다 */
    private suspend fun forgetDevice() {
        preferences.setRegisteredDevice(null)
        linkEvents.onDataDeleted()
    }

    private suspend fun registered(): RegisteredDevice? = preferences.registeredDevice.first()

    private suspend fun requireRegistered(): RegisteredDevice = registered() ?: throw DeviceNotRegisteredException()

    private fun ApiException.isDeviceGone() = status == 403 && slug == "device-not-registered"

    private fun newKey() = UUID.randomUUID().toString()
}

private fun DeviceRole.toNetwork() = when (this) {
    DeviceRole.SENIOR -> NetworkDeviceRole.PROTECTED
    DeviceRole.GUARDIAN -> NetworkDeviceRole.GUARDIAN
}

/** 서버 시각은 RFC 3339. 끝이 Z가 아닌 오프셋(+09:00)도 받는다 (API 26의 Instant.parse는 Z만 받음) */
internal fun String.toInstant(): Instant = OffsetDateTime.parse(this).toInstant()

internal fun NetworkLink.toModel() = FamilyLink(
    linkId = linkId,
    status = when (status) {
        NetworkLinkStatus.PENDING -> LinkStatus.PENDING
        NetworkLinkStatus.ACTIVE -> LinkStatus.ACTIVE
        NetworkLinkStatus.REVOKED -> LinkStatus.REVOKED
        NetworkLinkStatus.UNKNOWN -> LinkStatus.UNKNOWN
    },
    seniorName = protectedDevice.displayName,
    guardianName = guardianDevice.displayName,
    createdAt = createdAt.toInstant(),
    activatedAt = activatedAt?.toInstant(),
    lastCheckedAt = lastCheckedAt?.toInstant(),
    protection = protection?.let { ProtectionFlags(it.notifications, it.usageAccess, it.notificationListener) },
)
