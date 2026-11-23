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
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.inq_o.phonedoctor.core.data.FamilyRepository
import io.github.inq_o.phonedoctor.core.data.PushTokenSync
import io.github.inq_o.phonedoctor.core.network.ApiException
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** 새 FCM 토큰을 서버에 보낸다. 망 문제·서버 일시 오류면 다시 시도한다(토큰이 서버에 안 가면 다음 교체 때까지 푸시가 끊긴다) */
@HiltWorker
class PushTokenWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val family: FamilyRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val token = inputData.getString(KEY_TOKEN) ?: return Result.failure()
        return try {
            family.updatePushToken(token)
            Result.success()
        } catch (_: IOException) {
            Result.retry()
        } catch (e: ApiException) {
            if (e.status >= 500 || e.status == 429) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_TOKEN = "token"
    }
}

class WorkManagerPushTokenSync @Inject constructor(
    @ApplicationContext private val context: Context,
) : PushTokenSync {
    override fun schedule(token: String) {
        val request = OneTimeWorkRequestBuilder<PushTokenWorker>()
            .setInputData(workDataOf(PushTokenWorker.KEY_TOKEN to token))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // 더 새 토큰이 오면 앞의 것은 보낼 필요가 없다
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, request)
    }

    private companion object {
        const val UNIQUE = "push-token-sync"
    }
}
