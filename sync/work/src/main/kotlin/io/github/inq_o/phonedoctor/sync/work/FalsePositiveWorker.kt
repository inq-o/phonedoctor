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
import io.github.inq_o.phonedoctor.core.data.FalsePositive
import io.github.inq_o.phonedoctor.core.data.FalsePositiveReporter
import io.github.inq_o.phonedoctor.core.network.ApiException
import io.github.inq_o.phonedoctor.core.network.PhoneDoctorNetworkDataSource
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlertSource
import io.github.inq_o.phonedoctor.core.network.model.NetworkFalsePositiveReport
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import javax.inject.Inject

/** 오탐 익명 신고 한 건을 보낸다 (US7). 망 문제·서버 일시 오류면 다시 시도하고, 서버가 거절하면(4xx) 버린다 */
@HiltWorker
class FalsePositiveWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val network: PhoneDoctorNetworkDataSource,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val report = inputData.toReport() ?: return Result.failure()
        // 오래 실패하면 버린다 (재시도마다 App Check 토큰을 새로 받으므로)
        if (runAttemptCount >= MAX_ATTEMPTS) return Result.failure()
        return try {
            network.reportFalsePositive(report)
            Result.success()
        } catch (_: IOException) {
            Result.retry()
        } catch (e: ApiException) {
            // App Check 거절은 대개 일시적인 Play Integrity 오류다. 형식 오류(400) 같은 것만 버린다
            if (e.status >= 500 || e.status == 429 || e.status == 401) Result.retry() else Result.failure()
        }
    }

    private fun androidx.work.Data.toReport(): NetworkFalsePositiveReport? {
        val pkg = getString(KEY_PACKAGE) ?: return null
        val cert = getString(KEY_CERT) ?: return null
        val installScan = getBoolean(KEY_INSTALL_SCAN, false)
        return NetworkFalsePositiveReport(
            source = if (installScan) NetworkAlertSource.INSTALL_SCAN else NetworkAlertSource.SYMPTOM,
            packageName = pkg,
            versionCode = getLong(KEY_VERSION, -1).takeIf { it >= 0 } ?: return null,
            signingCertSha256 = cert,
            modelVersion = getString(KEY_MODEL),
            score = if (keyValueMap.containsKey(KEY_SCORE)) getDouble(KEY_SCORE, 0.0) else null,
            reasonCodes = getStringArray(KEY_REASONS)?.toList(),
            detectorVersion = getString(KEY_DETECTOR),
        )
    }

    companion object {
        const val MAX_ATTEMPTS = 10
        const val KEY_INSTALL_SCAN = "installScan"
        const val KEY_PACKAGE = "packageName"
        const val KEY_VERSION = "versionCode"
        const val KEY_CERT = "cert"
        const val KEY_MODEL = "modelVersion"
        const val KEY_SCORE = "score"
        const val KEY_REASONS = "reasonCodes"
        const val KEY_DETECTOR = "detectorVersion"
    }
}

private const val MIN_DELAY_MINUTES = 60L
private const val MAX_DELAY_MINUTES = 24 * 60L

class WorkManagerFalsePositiveReporter @Inject constructor(
    @ApplicationContext private val context: Context,
) : FalsePositiveReporter {
    override fun schedule(id: String, report: FalsePositive) {
        val data = workDataOf(
            FalsePositiveWorker.KEY_INSTALL_SCAN to report.installScan,
            FalsePositiveWorker.KEY_PACKAGE to report.packageName,
            FalsePositiveWorker.KEY_VERSION to report.versionCode,
            FalsePositiveWorker.KEY_CERT to report.signingCertSha256,
            FalsePositiveWorker.KEY_MODEL to report.modelVersion,
            FalsePositiveWorker.KEY_REASONS to report.reasonCodes?.toTypedArray(),
            FalsePositiveWorker.KEY_DETECTOR to report.detectorVersion,
            *listOfNotNull(report.score?.let { FalsePositiveWorker.KEY_SCORE to it }).toTypedArray(),
        )
        val request = OneTimeWorkRequestBuilder<FalsePositiveWorker>()
            .setInputData(data)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            // 응답(로그인한 요청)과 시각으로 이어지지 않게 1~24시간 뒤에 보낸다
            .setInitialDelay(Random.nextLong(MIN_DELAY_MINUTES, MAX_DELAY_MINUTES), TimeUnit.MINUTES)
            .build()
        // 한 번만 보내는 표시는 저장소(DataStore)가 한다. 여기서는 같은 작업이 겹쳐 예약되지 않게만
        WorkManager.getInstance(context).enqueueUniqueWork("false-positive-$id", ExistingWorkPolicy.KEEP, request)
    }
}
