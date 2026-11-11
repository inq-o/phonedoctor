package io.github.inq_o.phonedoctor.feature.seniorhome.impl

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.inq_o.phonedoctor.core.data.SymptomRepository
import io.github.inq_o.phonedoctor.core.designsystem.component.PdOutlinedButton
import io.github.inq_o.phonedoctor.core.model.DailyAppCount
import io.github.inq_o.phonedoctor.core.model.ScanResult
import io.github.inq_o.phonedoctor.core.model.ScanRun
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

data class ScanHistoryUiState(val apps: List<DailyAppCount> = emptyList(), val runs: List<ScanRun> = emptyList())

@HiltViewModel
class ScanHistoryViewModel @Inject constructor(repository: SymptomRepository) : ViewModel() {
    val uiState: StateFlow<ScanHistoryUiState> =
        combine(repository.dailyCounts(), repository.recentScans()) { apps, runs -> ScanHistoryUiState(apps, runs) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ScanHistoryUiState())
}

@Composable
fun ScanHistoryScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: ScanHistoryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ScanHistoryScreen(state, onBack, modifier)
}

/** S4 검사 기록. 살펴보지 못한 때(권한 꺼짐·잠금)도 그대로 보여 준다 */
@Composable
internal fun ScanHistoryScreen(state: ScanHistoryUiState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().safeDrawingPadding()) {
        Text(
            text = stringResource(R.string.feature_senior_home_impl_history_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp).semantics { heading() },
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 24.dp)) {
            item { Section(stringResource(R.string.feature_senior_home_impl_history_apps)) }
            if (state.apps.isEmpty()) {
                item { Line(stringResource(R.string.feature_senior_home_impl_history_apps_empty)) }
            }
            items(state.apps) { app ->
                val date = runCatching { LocalDate.parse(app.date) }.getOrNull()
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(app.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (date == null) {
                            app.date
                        } else {
                            stringResource(R.string.feature_senior_home_impl_history_app_row, date.monthValue, date.dayOfMonth, app.count)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                HorizontalDivider()
            }
            item { Section(stringResource(R.string.feature_senior_home_impl_history_runs)) }
            if (state.runs.isEmpty()) {
                item { Line(stringResource(R.string.feature_senior_home_impl_history_runs_empty)) }
            }
            items(state.runs) { run ->
                val time = zoned(run.startedAt)
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(
                        stringResource(R.string.feature_senior_home_impl_run_time, time.monthValue, time.dayOfMonth, timeOfDay(time)),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(runSentence(run), style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider()
            }
        }
        PdOutlinedButton(
            text = stringResource(R.string.feature_senior_home_impl_back),
            onClick = onBack,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        )
    }
}

@Composable
private fun runSentence(run: ScanRun): String = when (run.result) {
    ScanResult.OK -> when {
        run.quarantined -> stringResource(R.string.feature_senior_home_impl_run_quarantined)
        run.holdCount > 0 ->
            stringResource(R.string.feature_senior_home_impl_run_ok_hold, run.transitionCount, run.storedCount, run.holdCount)
        else -> stringResource(R.string.feature_senior_home_impl_run_ok, run.transitionCount, run.storedCount)
    }
    ScanResult.NO_PERMISSION -> stringResource(R.string.feature_senior_home_impl_run_no_permission)
    ScanResult.LOCKED -> stringResource(R.string.feature_senior_home_impl_run_locked)
    ScanResult.QUERY_NULL -> stringResource(R.string.feature_senior_home_impl_run_query_null)
    ScanResult.CLOCK_DISCARDED -> stringResource(R.string.feature_senior_home_impl_run_clock)
    ScanResult.ERROR -> stringResource(R.string.feature_senior_home_impl_run_error)
}

@Composable
private fun Section(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
private fun Line(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
}
