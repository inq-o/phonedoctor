package io.github.inq_o.phonedoctor.core.data.test

import io.github.inq_o.phonedoctor.core.data.ScanScheduler
import io.github.inq_o.phonedoctor.core.data.SymptomRepository
import io.github.inq_o.phonedoctor.core.data.UserSettingsRepository
import io.github.inq_o.phonedoctor.core.model.AlertOutcome
import io.github.inq_o.phonedoctor.core.model.AlertStatus
import io.github.inq_o.phonedoctor.core.model.AppIdentity
import io.github.inq_o.phonedoctor.core.model.DailyAppCount
import io.github.inq_o.phonedoctor.core.model.ScanRun
import io.github.inq_o.phonedoctor.core.model.SymptomAlert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

val fakeAlert = SymptomAlert(
    id = 1,
    app = AppIdentity("com.example.fastcleaner", "00", 1),
    label = "초고속 클리너",
    status = AlertStatus.OPEN,
    outcome = null,
    date = "2026-11-11",
    count = 5,
    days = 1,
    firstAt = 0,
    lastAt = 0,
    detectorVersion = "test",
    createdAt = 0,
    updatedAt = 0,
)

@Singleton
class FakeSymptomRepository @Inject constructor() : SymptomRepository {
    val alerts = MutableStateFlow(listOf(fakeAlert))

    override val openAlerts: Flow<List<SymptomAlert>> = alerts.map { list -> list.filter { it.status == AlertStatus.OPEN } }
    override val latestScan: Flow<ScanRun?> = flowOf(null)
    override val scanning: StateFlow<Boolean> = MutableStateFlow(false)

    override fun alert(id: Long): Flow<SymptomAlert?> = alerts.map { list -> list.firstOrNull { it.id == id } }
    override fun recentScans(limit: Int): Flow<List<ScanRun>> = flowOf(emptyList())
    override fun dailyCounts(days: Long): Flow<List<DailyAppCount>> = flowOf(emptyList())

    override suspend fun recordOutcome(id: Long, outcome: AlertOutcome) {
        alerts.value = alerts.value.map {
            if (it.id != id) it else it.copy(status = if (outcome == AlertOutcome.FAILED) it.status else AlertStatus.RESOLVED, outcome = outcome)
        }
    }

    override suspend fun confirmRemoved(id: Long, requested: Boolean): Boolean? = false
}

@Singleton
class FakeScanScheduler @Inject constructor() : ScanScheduler {
    val triggers = mutableListOf<String>()

    override fun ensurePeriodic() = Unit

    override fun scanNow(trigger: String) {
        triggers += trigger
    }
}

@Singleton
class FakeUserSettingsRepository @Inject constructor() : UserSettingsRepository {
    private val done = MutableStateFlow(true)
    override val onboardingDone: Flow<Boolean> = done

    override suspend fun completeOnboarding(disclosureVersion: Int) {
        done.value = true
    }
}
