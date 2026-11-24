package io.github.inq_o.phonedoctor.core.packages

import android.app.Activity
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 강력 모드 (US8, FR-031·032). 공장 초기화한 폰을 QR로 프로비저닝해 이 앱이 기기 소유자(Device Owner)가 됐을 때만 쓴다.
 * 보호자가 지우자고 하면 앱을 바로 정지(suspend)하고, 출처를 알 수 없는 앱 설치를 막을 수 있다.
 * 출시 빌드는 꺼짐 (STRONG_MODE_ENABLED, 관리자 수신기는 출시 매니페스트에서 뺌)
 */
interface StrongMode {
    /** 이 빌드가 강력 모드를 쓰고, 이 앱이 기기 소유자인지 */
    val active: Boolean

    /** 앱을 정지한다. 정지했으면 true (기기 소유자가 아니거나 시스템이 거절하면 false) */
    fun suspend(packageName: String): Boolean

    fun unsuspend(packageName: String): Boolean

    /** 정지한 앱 (설치된 앱 중) */
    fun suspendedPackages(): List<String>

    val unknownSourcesBlocked: Boolean

    fun setUnknownSourcesBlocked(blocked: Boolean)
}

@Singleton
class DevicePolicyStrongMode @Inject constructor(
    @ApplicationContext private val context: Context,
) : StrongMode {
    private val dpm get() = context.getSystemService(DevicePolicyManager::class.java)
    private val admin get() = ComponentName(context, StrongModeAdminReceiver::class.java)

    /** 우리가 멈춘 앱 (디지털 웰빙 같은 다른 기능이 멈춘 앱과 섞지 않게). 기기 안에만 (백업 안 함) */
    private val ours get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override val active: Boolean
        get() = BuildConfig.STRONG_MODE_ENABLED && dpm?.isDeviceOwnerApp(context.packageName) == true

    override fun suspend(packageName: String): Boolean = setSuspended(packageName, true)

    override fun unsuspend(packageName: String): Boolean = setSuspended(packageName, false)

    private fun setSuspended(packageName: String, suspended: Boolean): Boolean {
        // 우리 앱 자신은 정지하지 않는다
        if (!active || packageName == context.packageName) return false
        val ok = try {
            // 돌려준 배열은 정지하지 못한 앱 목록 (시스템은 홈·전화·설치기 같은 필수 앱을 거절한다)
            dpm.setPackagesSuspended(admin, arrayOf(packageName), suspended).isEmpty()
        } catch (e: SecurityException) {
            false
        }
        val set = ours.getStringSet(KEY_SUSPENDED, emptySet()).orEmpty()
        if (ok) ours.edit().putStringSet(KEY_SUSPENDED, if (suspended) set + packageName else set - packageName).apply()
        return ok
    }

    override fun suspendedPackages(): List<String> {
        if (!active) return emptyList()
        val set = ours.getStringSet(KEY_SUSPENDED, emptySet()).orEmpty()
        // 우리가 멈춘 앱 중 아직 멈춰 있는 것만 (지워졌거나 다른 길로 풀렸으면 목록에서 뺀다)
        val still = set.filter {
            try {
                dpm.isPackageSuspended(admin, it)
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
        if (still.size != set.size) ours.edit().putStringSet(KEY_SUSPENDED, still.toSet()).apply()
        return still.sorted()
    }

    /** Android 10+는 모든 설치 경로(기기 전체)를 막는 제한을 쓴다 */
    private val restriction: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY else UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES

    /** 실제로 걸린 제한으로 읽는다 (Android 14+ 정책 엔진은 기기 소유자의 제한을 전역 정책으로 둘 수 있다) */
    override val unknownSourcesBlocked: Boolean
        get() = active && context.getSystemService(UserManager::class.java).hasUserRestriction(restriction)

    override fun setUnknownSourcesBlocked(blocked: Boolean) {
        if (!active) return
        if (blocked) dpm.addUserRestriction(admin, restriction) else dpm.clearUserRestriction(admin, restriction)
    }
}

private const val PREFS = "strong_mode"
private const val KEY_SUSPENDED = "suspended"

/** 기기 관리자 수신기. 프로비저닝 때 시스템이 이 앱을 기기 소유자로 정한다 */
class StrongModeAdminReceiver : DeviceAdminReceiver()

/**
 * QR 프로비저닝(Android 12+ 필수): 관리 방식 묻기에는 "회사 소유 기기 전체 관리"로, 정책 확인에는 바로 통과로 답한다.
 * 설정 화면을 따로 두지 않는다 (강력 모드 설정은 앱 S7에서)
 */
class StrongModeProvisioningActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.action) {
            DevicePolicyManager.ACTION_GET_PROVISIONING_MODE -> setResult(
                RESULT_OK,
                Intent().putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE),
            )
            DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE -> setResult(RESULT_OK)
            else -> setResult(RESULT_CANCELED)
        }
        finish()
    }
}
