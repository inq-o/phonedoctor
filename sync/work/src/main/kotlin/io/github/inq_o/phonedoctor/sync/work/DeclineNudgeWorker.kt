package io.github.inq_o.phonedoctor.sync.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.inq_o.phonedoctor.core.data.DeclineNudgeScheduler
import io.github.inq_o.phonedoctor.core.data.SymptomRepository
import io.github.inq_o.phonedoctor.core.model.AlertOutcome
import io.github.inq_o.phonedoctor.core.model.AlertStatus
import io.github.inq_o.phonedoctor.core.model.FamilyDecision
import io.github.inq_o.phonedoctor.core.model.SymptomAlert
import io.github.inq_o.phonedoctor.core.notifications.Notifier
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** 삭제 창에서 취소한 지 하루 뒤 한 번: 아직 그대로(취소 상태이고 앱이 있음)면 다시 알린다 */
@HiltWorker
class DeclineNudgeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: SymptomRepository,
    private val notifier: Notifier,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, -1)
        nudgeIfStillDeclined(
            alert = { repository.alert(id).first() },
            stillInstalled = { repository.confirmRemoved(id, requested = false)?.not() },
            notify = notifier::notifyStillInstalled,
        )
        return Result.success()
    }

    companion object {
        const val KEY_ID = "alertId"
    }
}

/**
 * 취소한 뒤 다른 길로 지웠거나(설치 조회가 결과를 닫음), 근거가 다시 쌓여 요청이 다시 열렸으면 알리지 않는다.
 * 설치 여부를 확인하지 못하면(null) 알리지 않는다. 알렸으면 true
 */
internal suspend fun nudgeIfStillDeclined(
    alert: suspend () -> SymptomAlert?,
    stillInstalled: suspend () -> Boolean?,
    notify: (SymptomAlert) -> Unit,
): Boolean {
    // 가족이 지우자고 한 답이 남아 있을 때만 (연결을 끊거나 모든 데이터를 지우면 답이 지워진다)
    fun stillDeclined(x: SymptomAlert) = x.outcome == AlertOutcome.DECLINED && x.status == AlertStatus.RESOLVED &&
        x.family?.decision == FamilyDecision.RECOMMEND_UNINSTALL
    val a = alert() ?: return false
    if (!stillDeclined(a)) return false
    if (stillInstalled() != true) return false
    // 설치 조회 사이에 바뀌었을 수 있어 다시 읽는다
    val now = alert() ?: return false
    if (!stillDeclined(now)) return false
    notify(now)
    return true
}

class WorkManagerDeclineNudgeScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeclineNudgeScheduler {
    override fun schedule(alertId: Long) {
        val request = OneTimeWorkRequestBuilder<DeclineNudgeWorker>()
            .setInputData(workDataOf(DeclineNudgeWorker.KEY_ID to alertId))
            .setInitialDelay(1, TimeUnit.DAYS)
            .build()
        // 요청마다 하나: 다시 열렸다 또 취소하면 마지막 취소부터 하루 (같은 요청에서 거듭 취소해도 DECLINED는 한 번만 적혀 미뤄지지 않음)
        WorkManager.getInstance(context).enqueueUniqueWork("decline-nudge-$alertId", ExistingWorkPolicy.REPLACE, request)
    }
}
