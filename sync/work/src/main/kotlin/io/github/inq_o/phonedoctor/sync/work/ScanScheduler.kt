package io.github.inq_o.phonedoctor.sync.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.inq_o.phonedoctor.core.data.ScanScheduler
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * 15분은 WorkManager의 최소 반복 간격이다. 실제 실행은 Doze·대기 버킷·제조사 절전으로 더 늦어질 수 있어 상한이 아니다.
 * 포그라운드 서비스는 쓰지 않는다 (plan.md).
 */
class WorkManagerScanScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : ScanScheduler {
    override fun ensurePeriodic() {
        val request = PeriodicWorkRequestBuilder<SymptomScanWorker>(15, TimeUnit.MINUTES)
            .setInputData(workDataOf(SymptomScanWorker.KEY_TRIGGER to "periodic"))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun scanNow(trigger: String) {
        val request = OneTimeWorkRequestBuilder<SymptomScanWorker>()
            .setInputData(workDataOf(SymptomScanWorker.KEY_TRIGGER to trigger))
            .build()
        // 이미 도는 검사가 있으면 그 뒤에 한 번 더 돈다 (방금 생긴 이벤트를 놓치지 않게)
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private companion object {
        const val PERIODIC = "symptom-scan-periodic"
        const val NOW = "symptom-scan-now"
    }
}
