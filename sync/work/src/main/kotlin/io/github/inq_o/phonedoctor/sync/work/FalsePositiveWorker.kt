package io.github.inq_o.phonedoctor.sync.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
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
import io.github.inq_o.phonedoctor.core.data.FalsePositiveGate
import io.github.inq_o.phonedoctor.core.network.ApiException
import io.github.inq_o.phonedoctor.core.network.PhoneDoctorNetworkDataSource
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlertSource
import io.github.inq_o.phonedoctor.core.network.model.NetworkFalsePositiveReport
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** 오탐 익명 신고 한 건을 보낸다 (US7). 망 문제·서버 일시 오류면 다시 시도하고, 서버가 거절하면(4xx) 버린다 */
@HiltWorker
class FalsePositiveWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val network: PhoneDoctorNetworkDataSource,
    private val gate: FalsePositiveGate,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val report = inputData.toReport() ?: return Result.failure()
        val generation = inputData.getLong(KEY_GENERATION, -1)
        val deadline = inputData.getLong(KEY_DEADLINE, 0)
        val outcome = sendFalsePositive(
            report = report,
            attempts = runAttemptCount,
            gate = { send -> gate.send(generation, deadline, send) },
            send = network::reportFalsePositive,
        )
        return when (outcome) {
            SendOutcome.SENT, SendOutcome.DROPPED -> Result.success()
            SendOutcome.RETRY -> Result.retry()
            SendOutcome.FAILED -> Result.failure()
        }
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
        const val KEY_DEADLINE = "deadline"
        const val KEY_GENERATION = "generation"
        const val TAG = "false-positive"
    }
}

internal enum class SendOutcome { SENT, DROPPED, RETRY, FAILED }

/**
 * 보낼지·다시 할지 판단 (작업 밖에서 시험하려고 떼어 냄). [gate]는 세대·설정·지우는 중·마감(답한 뒤 24시간)을 보고
 * 보낼 수 있을 때만 [send]를 부르고 true를 준다 ([FalsePositiveGate.send])
 */
internal suspend fun sendFalsePositive(
    report: NetworkFalsePositiveReport,
    attempts: Int,
    gate: suspend (suspend () -> Unit) -> Boolean,
    send: suspend (NetworkFalsePositiveReport) -> Unit,
): SendOutcome {
    // 오래 실패하면 버린다 (재시도마다 App Check 토큰을 새로 받으므로)
    if (attempts >= FalsePositiveWorker.MAX_ATTEMPTS) return SendOutcome.FAILED
    return try {
        if (gate { send(report) }) SendOutcome.SENT else SendOutcome.DROPPED
    } catch (_: IOException) {
        SendOutcome.RETRY
    } catch (e: ApiException) {
        // App Check 거절은 대개 일시적인 Play Integrity 오류다. 형식 오류(400) 같은 것만 버린다
        if (e.status >= 500 || e.status == 429 || e.status == 401) SendOutcome.RETRY else SendOutcome.FAILED
    }
}

internal fun FalsePositive.toData(generation: Long, deadline: Long): Data = workDataOf(
    FalsePositiveWorker.KEY_GENERATION to generation,
    FalsePositiveWorker.KEY_INSTALL_SCAN to installScan,
    FalsePositiveWorker.KEY_PACKAGE to packageName,
    FalsePositiveWorker.KEY_VERSION to versionCode,
    FalsePositiveWorker.KEY_CERT to signingCertSha256,
    FalsePositiveWorker.KEY_MODEL to modelVersion,
    FalsePositiveWorker.KEY_REASONS to reasonCodes?.toTypedArray(),
    FalsePositiveWorker.KEY_DETECTOR to detectorVersion,
    FalsePositiveWorker.KEY_DEADLINE to deadline,
    *listOfNotNull(score?.let { FalsePositiveWorker.KEY_SCORE to it }).toTypedArray(),
)

internal fun Data.toReport(): NetworkFalsePositiveReport? {
    with(FalsePositiveWorker) {
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
}

class WorkManagerFalsePositiveReporter @Inject constructor(
    @ApplicationContext private val context: Context,
) : FalsePositiveReporter {
    override fun schedule(id: String, report: FalsePositive, generation: Long, deadline: Long, delayMillis: Long) {
        val data = report.toData(generation, deadline)
        val request = OneTimeWorkRequestBuilder<FalsePositiveWorker>()
            .setInputData(data)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            // 응답(로그인한 요청)과 시각으로 이어지지 않게 늦춰 보낸다 (늦출 시간은 FalsePositiveGate가 정함)
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .addTag(FalsePositiveWorker.TAG)
            .build()
        // 한 번만 보내는 표시는 저장소(DataStore)가 한다. 여기서는 같은 작업이 겹쳐 예약되지 않게만
        WorkManager.getInstance(context).enqueueUniqueWork("false-positive-$id", ExistingWorkPolicy.KEEP, request)
    }

    override fun cancelAll() {
        WorkManager.getInstance(context).cancelAllWorkByTag(FalsePositiveWorker.TAG)
    }
}
