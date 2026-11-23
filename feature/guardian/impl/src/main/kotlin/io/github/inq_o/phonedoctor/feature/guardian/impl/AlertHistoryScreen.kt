package io.github.inq_o.phonedoctor.feature.guardian.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianOutlinedButton
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianPrimaryButton
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianScreen

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

/** G3 요청 기록 (90일). 알림 전용 요청은 '경고 앱'으로 */
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
        modifier = modifier,
        actions = {
            GuardianOutlinedButton(stringResource(R.string.feature_guardian_impl_back), onBack)
            if (state is AlertHistoryUiState.Failed) GuardianPrimaryButton(stringResource(R.string.feature_guardian_impl_retry), onRetry)
        },
    ) {
        when (state) {
            AlertHistoryUiState.Loading -> CircularProgressIndicator()
            is AlertHistoryUiState.Failed -> {
                Headline(stringResource(R.string.feature_guardian_impl_history_load_failed), announce = true)
                Body(state.error.message())
            }
            is AlertHistoryUiState.Ready ->
                if (state.alerts.isEmpty()) {
                    Body(stringResource(R.string.feature_guardian_impl_history_empty))
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.alerts.forEach { AlertCard(it) { onOpen(it.alertId) } }
                    }
                }
        }
    }
}
