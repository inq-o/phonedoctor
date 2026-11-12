package io.github.inq_o.phonedoctor.core.packages

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 시스템이 돌려준 삭제 결과. 화면이 다시 보일 때 설치 여부를 한 번 더 조회해 확정한다 */
sealed interface UninstallResult {
    val packageName: String

    data class Success(override val packageName: String) : UninstallResult

    /** 시니어가 시스템 확인창에서 취소 */
    data class Cancelled(override val packageName: String) : UninstallResult

    data class Failed(override val packageName: String, val status: Int, val message: String?) : UninstallResult
}

interface AppUninstaller {
    val results: SharedFlow<UninstallResult>

    /** 시스템 삭제 확인창을 요청한다. 앱이 화면 앞에 있을 때만 부른다 (백그라운드 액티비티 실행 제한) */
    fun requestUninstall(packageName: String)
}

@Singleton
class PackageInstallerUninstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppUninstaller {
    override val results: SharedFlow<UninstallResult> get() = UninstallResults.flow

    override fun requestUninstall(packageName: String) {
        val intent = Intent(context, UninstallResultReceiver::class.java)
            .setAction(UninstallResultReceiver.ACTION)
            .putExtra(UninstallResultReceiver.EXTRA_PACKAGE, packageName)
        // 시스템이 결과 extra를 채워 보내므로 변경 가능해야 한다. 수신자는 명시적 컴포넌트로 고정
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val sender = PendingIntent.getBroadcast(context, packageName.hashCode(), intent, flags).intentSender
        context.packageManager.packageInstaller.uninstall(packageName, sender)
    }
}

internal object UninstallResults {
    val flow = MutableSharedFlow<UninstallResult>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
}

class UninstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) {
                    UninstallResults.flow.tryEmit(UninstallResult.Failed(pkg, status, "확인창 정보 없음"))
                    return
                }
                try {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: RuntimeException) {
                    Log.w(TAG, "삭제 확인창을 열지 못함", e)
                    UninstallResults.flow.tryEmit(UninstallResult.Failed(pkg, status, e.javaClass.simpleName))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> UninstallResults.flow.tryEmit(UninstallResult.Success(pkg))
            PackageInstaller.STATUS_FAILURE_ABORTED -> UninstallResults.flow.tryEmit(UninstallResult.Cancelled(pkg))
            else -> UninstallResults.flow.tryEmit(
                UninstallResult.Failed(pkg, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)),
            )
        }
    }

    companion object {
        const val ACTION = "io.github.inq_o.phonedoctor.core.packages.UNINSTALL_RESULT"
        const val EXTRA_PACKAGE = "package"
        private const val TAG = "PhoneDoctorUninstall"
    }
}
