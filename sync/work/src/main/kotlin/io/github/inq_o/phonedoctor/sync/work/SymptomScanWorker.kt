package io.github.inq_o.phonedoctor.sync.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.inq_o.phonedoctor.core.data.SymptomScanner
import io.github.inq_o.phonedoctor.core.data.FamilyRepository
import io.github.inq_o.phonedoctor.core.data.FamilySyncScheduler
import io.github.inq_o.phonedoctor.core.model.DeviceRole
import io.github.inq_o.phonedoctor.core.data.UserSettingsRepository
import io.github.inq_o.phonedoctor.core.data.isGuardianPhone
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
    private val family: FamilyRepository,
    private val familySync: FamilySyncScheduler,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // 보호자 폰은 자기 앱을 살펴보지 않는다 (주기 작업은 앱이 뜰 때 역할과 관계없이 걸린다)
        if (isGuardianPhone(settings.chosenRole.first(), family.role.first(), family.available)) return Result.success()
        val report = scanner.scan(inputData.getString(KEY_TRIGGER) ?: "periodic")
        report.closed.forEach(notifier::cancelAlert)
        report.opened.forEach(notifier::notifyAlert)
        // 가족과 연결한 시니어 폰: 새 근거·결과를 보내고, 6시간마다 보호 상태를 알린다
        if (family.role.first() == DeviceRole.SENIOR) familySync.schedule()
        return Result.success()
    }

    companion object {
        const val KEY_TRIGGER = "trigger"
    }
}
