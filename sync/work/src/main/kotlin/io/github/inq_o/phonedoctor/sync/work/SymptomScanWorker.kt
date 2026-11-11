package io.github.inq_o.phonedoctor.sync.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.inq_o.phonedoctor.core.data.SymptomScanner
import io.github.inq_o.phonedoctor.core.notifications.Notifier

/** 증상 감지 검사 1회를 돌리고, 새로 열린 확인 요청을 시니어에게 알린다 */
@HiltWorker
class SymptomScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scanner: SymptomScanner,
    private val notifier: Notifier,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val report = scanner.scan(inputData.getString(KEY_TRIGGER) ?: "periodic")
        report.closed.forEach(notifier::cancelAlert)
        report.opened.forEach(notifier::notifyAlert)
        return Result.success()
    }

    companion object {
        const val KEY_TRIGGER = "trigger"
    }
}
