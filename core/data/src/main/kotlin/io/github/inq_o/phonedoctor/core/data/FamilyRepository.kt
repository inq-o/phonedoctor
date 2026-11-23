package io.github.inq_o.phonedoctor.core.data

import io.github.inq_o.phonedoctor.core.datastore.RegisteredDevice
import io.github.inq_o.phonedoctor.core.datastore.UserPreferences
import io.github.inq_o.phonedoctor.core.model.DeviceRole
import io.github.inq_o.phonedoctor.core.model.FamilyLink
import io.github.inq_o.phonedoctor.core.model.LinkStatus
import io.github.inq_o.phonedoctor.core.model.PairingCode
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
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Qualifier

/**
 * 가족 연결 (US3, ux-flows F2·F4). 서버와 처음 통신하는 때는 연결을 시작할 때다:
 * 시니어는 [createPairingCode], 보호자는 [acceptPairingCode]를 처음 부를 때 로그인하고 기기를 등록한다. 그 전에는 아무것도 보내지 않는다.
 * 실패는 서버가 답한 오류면 ApiException, 망 문제면 IOException으로 던진다
 */
interface FamilyRepository {
    /** 이 빌드에서 가족 연결을 쓸 수 있는지. false면 화면에서 진입점을 숨긴다 (출시 빌드는 개인정보처리방침을 고치기 전까지 false) */
    val available: Boolean

    /** 서버에 등록한 역할. 연결을 시작하기 전이면 null */
    val role: Flow<DeviceRole?>

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

    /** FCM 토큰이 바뀌었을 때. 등록 전이면 아무것도 안 한다(다음 등록 때 새 토큰을 보냄) */
    suspend fun updatePushToken(token: String)

    /** 모든 데이터 지우기 (S7, 개인정보처리방침 3·6절): 서버의 기기와 연결·요청을 지우고 Firebase 사용자도 지운다 */
    suspend fun deleteAllData()
}

/** 보호자는 Google 로그인 사용자만 서버에 등록할 수 있다 (openapi POST /devices). 화면은 O-G1로 보낸다 */
class GuardianSignInRequiredException : IllegalStateException("보호자는 Google 로그인이 필요함")

/** 이미 다른 역할로 등록한 기기. 역할을 바꾸려면 모든 데이터 지우기 뒤 다시 */
class RoleMismatchException(val registered: DeviceRole) : IllegalStateException("이미 ${registered.name} 역할로 등록됨")

/** Firebase 로그인 (시니어 익명, 보호자 Google). 시험에서는 가짜로 바꾼다 */
interface FamilyAccount {
    val isSignedIn: Boolean
    val isAnonymous: Boolean
    suspend fun signInAnonymously()

    /** 지금 사용자를 지우고 로그아웃. 로그인 전이면 아무것도 안 한다 */
    suspend fun deleteUser()
}

/** 서버가 푸시를 보낼 이 기기의 FCM 토큰 */
fun interface PushTokens {
    suspend fun token(): String
}

/** 등록 때 보내는 기기 정보 (data-model Device) */
interface DeviceInfo {
    val appVersionCode: Int
    val sdkInt: Int
    val manufacturer: String
    val locale: String
}

/** 가족 연결 켜짐 여부 (core:network FamilyFeature.ENABLED) */
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
) : FamilyRepository {
    /** 등록·삭제가 겹치지 않게 (예: 토큰 갱신과 코드 받기가 동시에) */
    private val mutex = Mutex()

    override val role: Flow<DeviceRole?> = preferences.registeredDevice.map { it?.let { d -> DeviceRole.valueOf(d.role) } }

    override suspend fun createPairingCode(): PairingCode {
        ensureRegistered(DeviceRole.SENIOR)
        val code = network.createPairingCode(newKey())
        return PairingCode(code.code, code.qrPayload, code.expiresAt.toInstant())
    }

    override suspend fun acceptPairingCode(code: String): FamilyLink {
        ensureRegistered(DeviceRole.GUARDIAN)
        return network.acceptPairingCode(newKey(), code).toModel()
    }

    override suspend fun links(): List<FamilyLink> {
        checkAvailable()
        registered() ?: return emptyList()
        return network.listLinks().map { it.toModel() }
    }

    override suspend fun confirmLink(linkId: String): FamilyLink {
        requireRegistered()
        return network.confirmLink(newKey(), linkId).toModel()
    }

    override suspend fun revokeLink(linkId: String) {
        requireRegistered()
        network.revokeLink(linkId)
    }

    override suspend fun updatePushToken(token: String) {
        if (!available) return
        mutex.withLock {
            val device = registered() ?: return
            network.updateDevice(device.deviceId, NetworkDeviceUpdate(fcmToken = token))
        }
    }

    override suspend fun deleteAllData() {
        checkAvailable()
        mutex.withLock {
            registered()?.let { device ->
                try {
                    network.deleteDevice(device.deviceId)
                } catch (e: ApiException) {
                    // 이미 없음 (오래 안 써서 서버가 지움 등). 지우려던 결과와 같다
                    if (e.status != 404) throw e
                }
                // 서버에서 지운 뒤 바로 비운다. 아래 Firebase 삭제가 실패해 다시 눌러도 서버 삭제를 되풀이하지 않게
                preferences.setRegisteredDevice(null)
            }
            account.deleteUser()
        }
    }

    private suspend fun ensureRegistered(role: DeviceRole): String {
        checkAvailable()
        return mutex.withLock {
            registered()?.let { device ->
                val registeredRole = DeviceRole.valueOf(device.role)
                if (registeredRole != role) throw RoleMismatchException(registeredRole)
                return@withLock device.deviceId
            }
            when (role) {
                DeviceRole.SENIOR -> if (!account.isSignedIn) account.signInAnonymously()
                DeviceRole.GUARDIAN -> if (!account.isSignedIn || account.isAnonymous) throw GuardianSignInRequiredException()
            }
            // 서버는 같은 Firebase 사용자의 재등록을 갱신으로 처리한다. 응답을 못 받고 다시 불러도 기기가 둘 생기지 않는다
            val device = network.registerDevice(
                newKey(),
                NetworkDeviceRegistration(
                    role = role.toNetwork(),
                    fcmToken = pushTokens.token(),
                    appVersionCode = deviceInfo.appVersionCode,
                    sdkInt = deviceInfo.sdkInt,
                    manufacturer = deviceInfo.manufacturer,
                    locale = deviceInfo.locale,
                ),
            )
            preferences.setRegisteredDevice(RegisteredDevice(device.deviceId, role.name))
            device.deviceId
        }
    }

    private suspend fun registered(): RegisteredDevice? = preferences.registeredDevice.first()

    private suspend fun requireRegistered(): RegisteredDevice {
        checkAvailable()
        return registered() ?: throw IllegalStateException("연결을 시작하기 전")
    }

    private fun checkAvailable() = check(available) { "이 빌드는 가족 연결이 꺼져 있음" }

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
)
