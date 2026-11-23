package io.github.inq_o.phonedoctor.sync.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.inq_o.phonedoctor.core.data.SymptomScanner
import io.github.inq_o.phonedoctor.core.data.UserSettingsRepository
import io.github.inq_o.phonedoctor.core.model.DeviceRole
import io.github.inq_o.phonedoctor.core.notifications.Notifier
import kotlinx.coroutines.flow.first

/** 증상 감지 검사 1회를 돌리고, 새로 열린 확인 요청을 시니어에게 알린다 */
@HiltWorker
class SymptomScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scanner: SymptomScanner,
    private val notifier: Notifier,
    private val settings: UserSettingsRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // 보호자 폰은 자기 앱을 살펴보지 않는다 (주기 작업은 앱이 뜰 때 역할과 관계없이 걸린다)
        if (settings.chosenRole.first() == DeviceRole.GUARDIAN) return Result.success()
        val report = scanner.scan(inputData.getString(KEY_TRIGGER) ?: "periodic")
        report.closed.forEach(notifier::cancelAlert)
        report.opened.forEach(notifier::notifyAlert)
        return Result.success()
    }

    companion object {
        const val KEY_TRIGGER = "trigger"
    }
}
