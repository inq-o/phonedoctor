package io.github.inq_o.phonedoctor.core.usage

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** 사용 기록 접근(특수 권한) 상태 */
interface UsageAccess {
    fun isGranted(): Boolean

    /** 사용 기록 접근 설정 화면. 이 앱 항목을 바로 여는 것과 목록을 여는 것 순서로 시도한다 */
    fun settingsIntents(): List<Intent>
}

class AndroidUsageAccess @Inject constructor(
    @ApplicationContext private val context: Context,
) : UsageAccess {
    override fun isGranted(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    override fun settingsIntents(): List<Intent> = listOf(
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
    )
}
