package io.github.inq_o.phonedoctor.sync.work

import android.content.Context
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
    override fun schedule() {
        if (!available) return
        val request = OneTimeWorkRequestBuilder<FamilySyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // 기다리는 작업은 새 작업으로 바꾼다(REPLACE): 서버 오류로 재시도 대기가 길어져도(최대 5시간) 새 확인 요청이나
        // 15분마다 검사가 부르면 바로 다시 보내므로, 보호자 알림이 검사 주기 넘게 묶이지 않는다. 작업은 하나뿐이라 쌓이지 않는다.
        // 도는 중이면 끊지 않고 뒤에 붙인다(APPEND_OR_REPLACE): 보내던 요청이 중간에 끊겨 다시 보내지 않게
        val workManager = WorkManager.getInstance(context)
        val current = workManager.getWorkInfosForUniqueWork(UNIQUE)
        current.addListener({
            val running = runCatching { current.get() }.getOrNull().orEmpty().any { it.state == WorkInfo.State.RUNNING }
            workManager.enqueueUniqueWork(UNIQUE, if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE, request)
        }, Runnable::run)
    }

    private companion object {
        const val UNIQUE = "family-sync"
    }
}
