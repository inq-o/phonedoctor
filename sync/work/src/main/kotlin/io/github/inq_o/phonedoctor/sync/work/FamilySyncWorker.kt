package io.github.inq_o.phonedoctor.sync.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
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
        // 도는 중에 새로 생긴 일은 그 뒤에 한 번 더 (방금 기록한 결과를 놓치지 않게)
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private companion object {
        const val UNIQUE = "family-sync"
    }
}
