package io.github.inq_o.phonedoctor.core.data

import io.github.inq_o.phonedoctor.core.database.AlertLocalDao
import io.github.inq_o.phonedoctor.core.database.ScanRunDao
import io.github.inq_o.phonedoctor.core.database.SymptomEventDao
import io.github.inq_o.phonedoctor.core.model.AlertOutcome
import io.github.inq_o.phonedoctor.core.model.AlertStatus
import io.github.inq_o.phonedoctor.core.model.DailyAppCount
import io.github.inq_o.phonedoctor.core.model.ScanResult
import io.github.inq_o.phonedoctor.core.model.ScanRun
import io.github.inq_o.phonedoctor.core.model.SymptomAlert
import io.github.inq_o.phonedoctor.core.packages.InstalledApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject

interface SymptomRepository {
    /** 확인이 필요한 앱 (열린 요청), 최근 광고 순 */
    val openAlerts: Flow<List<SymptomAlert>>

    /** 사용 기록을 끝까지 살펴본 마지막 검사. 권한이 없거나 잠겨서 못 본 검사는 빼고 센다 */
    val latestScan: Flow<ScanRun?>

    val scanning: StateFlow<Boolean>

    fun alert(id: Long): Flow<SymptomAlert?>

    fun recentScans(limit: Int = 30): Flow<List<ScanRun>>

    /** 최근 [days]일의 앱별 하루 횟수 */
    fun dailyCounts(days: Long = 7): Flow<List<DailyAppCount>>

    /** 삭제 시도의 결과를 남긴다. 실패는 요청을 닫지 않는다 */
    suspend fun recordOutcome(id: Long, outcome: AlertOutcome)

    /**
     * 앱이 실제로 없어졌는지 다시 조회해, 없으면 요청을 닫는다. [requested]는 이 앱에서 삭제를 요청했는지.
     * 없어졌으면 true, 아직 있으면 false, 확인하지 못했으면 null.
     */
    suspend fun confirmRemoved(id: Long, requested: Boolean): Boolean?
}

class DefaultSymptomRepository @Inject constructor(
    private val alertDao: AlertLocalDao,
    private val scanRunDao: ScanRunDao,
    private val symptomDao: SymptomEventDao,
    private val installedApps: InstalledApps,
    scanner: SymptomScanner,
) : SymptomRepository {
    override val openAlerts: Flow<List<SymptomAlert>> = alertDao.observeOpen().map { list -> list.map { it.toModel() } }

    override val latestScan: Flow<ScanRun?> = scanRunDao.observeLatest(ScanResult.OK.name).map { it?.toModel() }

    override val scanning: StateFlow<Boolean> = scanner.scanning

    override fun alert(id: Long): Flow<SymptomAlert?> = alertDao.observe(id).map { it?.toModel() }

    override fun recentScans(limit: Int): Flow<List<ScanRun>> = scanRunDao.observeRecent(limit).map { list -> list.map { it.toModel() } }

    override fun dailyCounts(days: Long): Flow<List<DailyAppCount>> =
        symptomDao.observeDailyCounts(LocalDate.now().minusDays(days - 1).toString())
            .map { list ->
                // 이미 지운 앱은 이름을 읽을 수 없어 확인 요청에 남긴 이름을 쓴다
                val saved = alertDao.all().associate { it.packageName to it.label }
                list.map {
                    val label = installedApps.label(it.packageName) ?: saved[it.packageName] ?: it.packageName
                    DailyAppCount(it.packageName, label, it.localDate, it.count)
                }
            }
            .flowOn(Dispatchers.IO)

    override suspend fun recordOutcome(id: Long, outcome: AlertOutcome) {
        val alert = alertDao.get(id) ?: return
        val status = if (outcome == AlertOutcome.FAILED) alert.status else AlertStatus.RESOLVED.name
        alertDao.update(alert.copy(status = status, outcome = outcome.name, updatedAt = System.currentTimeMillis()))
    }

    override suspend fun confirmRemoved(id: Long, requested: Boolean): Boolean? = withContext(Dispatchers.IO) {
        val alert = alertDao.get(id) ?: return@withContext null
        val installed = installedApps.isInstalled(alert.packageName) ?: return@withContext null
        if (!installed && alert.outcome != AlertOutcome.UNINSTALLED.name) {
            recordOutcome(id, if (requested) AlertOutcome.UNINSTALLED else AlertOutcome.ALREADY_REMOVED)
        }
        !installed
    }
}
