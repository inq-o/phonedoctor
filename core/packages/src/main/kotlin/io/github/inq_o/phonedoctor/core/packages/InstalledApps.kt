package io.github.inq_o.phonedoctor.core.packages

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.inq_o.phonedoctor.core.model.AppIdentity
import java.security.MessageDigest
import javax.inject.Inject

/** 판정에 쓰는 설치 앱 정보 */
data class InstalledApp(
    val packageName: String,
    val isSystem: Boolean,
    /** 런처 아이콘이 여는 실제 액티비티(별칭이면 targetActivity). 아이콘이 없으면 빈 집합 */
    val launcherTargets: Set<String>,
    /** 마지막 설치·업데이트 시각 (벽시계 ms) */
    val lastUpdateTime: Long,
)

interface InstalledApps {
    /** 이 앱의 패키지 이름 (판정 대상에서 뺀다) */
    val ownPackageName: String

    /** 지금 설치된 앱 전체. 조회에 실패하면 예외를 그대로 낸다 (빈 목록을 "앱 없음"으로 읽지 않게) */
    fun snapshot(): Map<String, InstalledApp>

    /** 지금 설치된 판의 식별 정보. 없거나 서명을 못 읽으면 null */
    fun identity(packageName: String): AppIdentity?

    fun label(packageName: String): String?

    /** 설치돼 있으면 true, 없으면 false, 확인하지 못하면 null */
    fun isInstalled(packageName: String): Boolean?
}

class AndroidInstalledApps @Inject constructor(
    @ApplicationContext private val context: Context,
) : InstalledApps {
    private val pm get() = context.packageManager

    override val ownPackageName: String get() = context.packageName

    override fun snapshot(): Map<String, InstalledApp> {
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchers = pm.queryIntentActivities(main, PackageManager.MATCH_ALL)
            .groupBy({ it.activityInfo.packageName }, { it.activityInfo.targetActivity ?: it.activityInfo.name })
        return pm.getInstalledPackages(0).associate { info ->
            val flags = info.applicationInfo?.flags ?: 0
            info.packageName to InstalledApp(
                packageName = info.packageName,
                isSystem = flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                launcherTargets = launchers[info.packageName]?.toSet() ?: emptySet(),
                lastUpdateTime = info.lastUpdateTime,
            )
        }
    }

    override fun identity(packageName: String): AppIdentity? {
        val info = packageInfo(packageName, signing = true) ?: return null
        val cert = signingCert(info) ?: return null
        return AppIdentity(packageName, sha256(cert), PackageInfoCompat.getLongVersionCode(info))
    }

    override fun label(packageName: String): String? =
        packageInfo(packageName, signing = false)?.applicationInfo?.let { pm.getApplicationLabel(it).toString() }

    override fun isInstalled(packageName: String): Boolean? = try {
        pm.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    } catch (e: RuntimeException) {
        null
    }

    private fun packageInfo(packageName: String, signing: Boolean): PackageInfo? = try {
        @Suppress("DEPRECATION")
        val flags = when {
            !signing -> 0
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> PackageManager.GET_SIGNING_CERTIFICATES
            else -> PackageManager.GET_SIGNATURES
        }
        pm.getPackageInfo(packageName, flags)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun signingCert(info: PackageInfo): ByteArray? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val s = info.signingInfo ?: return null
            // 여러 서명자면 현재 서명 중 첫 번째, 아니면 이력의 마지막(지금 쓰는 인증서)
            (if (s.hasMultipleSigners()) s.apkContentsSigners.firstOrNull() else s.signingCertificateHistory?.lastOrNull())
                ?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.toByteArray()
        }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
