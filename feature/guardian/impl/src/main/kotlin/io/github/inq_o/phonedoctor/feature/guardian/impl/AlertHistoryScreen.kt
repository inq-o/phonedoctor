package io.github.inq_o.phonedoctor.feature.guardian.impl

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianBand
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianPrimaryButton
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianRowDivider
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianScreen
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianSectionHeader
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun AlertHistoryScreen(
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AlertHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    AlertHistoryScreen(state, onOpen, viewModel::refresh, onBack, modifier)
}

/** G3 지난 요청 (90일, ux-flows §4 G3): 날짜 묶음 아래 흰 띠 목록, 줄마다 결과 배지 하나 */
@Composable
internal fun AlertHistoryScreen(
    state: AlertHistoryUiState,
    onOpen: (String) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GuardianScreen(
        title = stringResource(R.string.feature_guardian_impl_history_title),
        onBack = onBack,
        backLabel = stringResource(R.string.feature_guardian_impl_back),
        contentPadding = 0.dp,
        modifier = modifier,
        actions = if (state is AlertHistoryUiState.Failed) ({ GuardianPrimaryButton(stringResource(R.string.feature_guardian_impl_retry), onRetry) }) else null,
    ) {
        when (state) {
            AlertHistoryUiState.Loading -> CircularProgressIndicator(Modifier.padding(16.dp))
            is AlertHistoryUiState.Failed -> Column(Modifier.padding(horizontal = 16.dp)) {
                Headline(stringResource(R.string.feature_guardian_impl_history_load_failed), announce = true)
                Body(state.error.message())
            }
            is AlertHistoryUiState.Ready ->
                if (state.alerts.isEmpty()) {
                    Column(Modifier.padding(horizontal = 16.dp)) { Body(stringResource(R.string.feature_guardian_impl_history_empty)) }
                } else {
                    val zone = ZoneId.systemDefault()
                    val today = LocalDate.now(zone)
                    // 폰이 여럿이면 줄마다 폰 이름을 붙인다
                    val showPhone = state.phoneCount > 1
                    state.alerts.groupBy { it.createdAt.atZone(zone).toLocalDate() }.entries.forEachIndexed { i, (day, alerts) ->
                        GuardianSectionHeader(
                            if (day == today) stringResource(R.string.feature_guardian_impl_history_today) else stringResource(R.string.feature_guardian_impl_history_day, day.monthValue, day.dayOfMonth),
                            topPadding = if (i == 0) 0.dp else 24.dp,
                        )
                        GuardianBand {
                            alerts.forEachIndexed { j, alert ->
                                if (j > 0) GuardianRowDivider()
                                AlertRow(alert, showPhone = showPhone, onClick = { onOpen(alert.alertId) })
                            }
                        }
                    }
                }
        }
    }
}
