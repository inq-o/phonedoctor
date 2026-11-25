package io.github.inq_o.phonedoctor.sync.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.inq_o.phonedoctor.core.data.FamilyAlertSync
import io.github.inq_o.phonedoctor.core.data.FamilyEnabled
import io.github.inq_o.phonedoctor.core.data.FamilyRepository
import io.github.inq_o.phonedoctor.core.data.FamilySyncScheduler
import io.github.inq_o.phonedoctor.core.notifications.Notifier
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * 시니어 폰 → 가족 서버: 확인 요청·처리 결과·보호 상태 보내기, 놓친 보호자 응답 받아 오기 (FamilyAlertSync).
 * 망 문제로 못 보낸 것이 있으면 다시 시도한다
 */
@HiltWorker
class FamilySyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sync: FamilyAlertSync,
    private val family: FamilyRepository,
    private val notifier: Notifier,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val result = sync.sync()
        // 푸시를 놓쳐 여기서 처음 안 응답도 시니어에게 알린다
        result.decisions.filter { it.notify }.forEach { notifier.notifyDecision(it.alert, it.decision, it.guardianName) }
        try {
            family.reportAppVersion()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 다음 동기화 때 다시
        }
        return if (result.complete) Result.success() else Result.retry()
    }
}

class WorkManagerFamilySyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    @FamilyEnabled private val available: Boolean,
) : FamilySyncScheduler {
    override fun schedule(urgent: Boolean) {
        if (!available) return
        val request = OneTimeWorkRequestBuilder<FamilySyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        val workManager = WorkManager.getInstance(context)
        val current = workManager.getWorkInfosForUniqueWork(UNIQUE)
        current.addListener({
            try {
                val infos = current.get().filterNot { it.state.isFinished }
                val policy = familySyncPolicy(
                    urgent = urgent,
                    running = infos.any { it.state == WorkInfo.State.RUNNING },
                    nextRunAt = infos.filter { it.state == WorkInfo.State.ENQUEUED }.maxOfOrNull { it.nextScheduleTimeMillis },
                    now = System.currentTimeMillis(),
                )
                workManager.enqueueUniqueWork(UNIQUE, policy, request)
            } catch (e: Exception) {
                // 리스너 안 예외는 future가 삼키므로 남긴다 (다음 검사가 다시 예약)
                Log.w(TAG, "가족 동기화 예약 실패", e)
            }
        }, Runnable::run)
    }

    private companion object {
        const val UNIQUE = "family-sync"
        const val TAG = "FamilySync"
    }
}

/** 재시도 대기가 이보다 길게 남았으면 주기 검사도 다시 시작한다: 서버가 잠깐 실패한 뒤 보호자 알림이 최대 5시간(WorkManager 상한) 묶이지 않게 */
internal const val MAX_FAMILY_SYNC_WAIT_MS = 30 * 60 * 1000L

/**
 * 가족 동기화 예약 방식 (시험하려고 떼어 냄). 작업은 하나(unique)뿐이라 쌓이지 않는다.
 * - 도는 중: 급하면 뒤에 붙이고(APPEND_OR_REPLACE, 보내던 요청을 끊지 않게), 아니면 그대로
 * - 기다리는 중: 급하거나 대기가 [MAX_FAMILY_SYNC_WAIT_MS]보다 길면 새로(REPLACE), 아니면 그대로(재시도 간격 유지)
 */
internal fun familySyncPolicy(urgent: Boolean, running: Boolean, nextRunAt: Long?, now: Long): ExistingWorkPolicy = when {
    running -> if (urgent) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
    nextRunAt == null -> ExistingWorkPolicy.KEEP
    urgent || nextRunAt - now > MAX_FAMILY_SYNC_WAIT_MS -> ExistingWorkPolicy.REPLACE
    else -> ExistingWorkPolicy.KEEP
}
