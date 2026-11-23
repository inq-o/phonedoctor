package io.github.inq_o.phonedoctor.feature.guardian.impl

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianOutlinedButton
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianPrimaryButton
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianScreen
import io.github.inq_o.phonedoctor.core.designsystem.theme.PhoneDoctorTheme

/**
 * @param onHome 홈(G1)으로. 기다리는 중에 닫아도 연결되면 알림이 온다
 * @param onRetry 시니어가 거절함 → 새 코드 넣기
 */
@Composable
fun LinkWaitingScreen(
    linkId: String,
    seniorName: String,
    onHome: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LinkWaitingViewModel = hiltViewModel<LinkWaitingViewModel, LinkWaitingViewModel.Factory>(
        key = "waiting-$linkId",
        creationCallback = { it.create(linkId, seniorName) },
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.startWatching()
        onPauseOrDispose { viewModel.stopWatching() }
    }
    LinkWaitingScreen(state = state, onHome = onHome, onRetry = onRetry, modifier = modifier)
}

@Composable
internal fun LinkWaitingScreen(
    state: LinkWaitingUiState,
    onHome: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = seniorName(state.seniorName)
    GuardianScreen(
        title = null,
        modifier = modifier,
        actions = {
            when (state) {
                is LinkWaitingUiState.Waiting -> GuardianOutlinedButton(stringResource(R.string.feature_guardian_impl_waiting_later), onHome)
                is LinkWaitingUiState.Connected -> GuardianPrimaryButton(stringResource(R.string.feature_guardian_impl_home), onHome)
                is LinkWaitingUiState.Declined -> {
                    GuardianOutlinedButton(stringResource(R.string.feature_guardian_impl_home), onHome)
                    GuardianPrimaryButton(stringResource(R.string.feature_guardian_impl_waiting_retry), onRetry)
                }
            }
        },
    ) {
        when (state) {
            is LinkWaitingUiState.Waiting -> {
                Headline(stringResource(R.string.feature_guardian_impl_waiting_title))
                Body(stringResource(R.string.feature_guardian_impl_waiting_body, name))
                Body(stringResource(R.string.feature_guardian_impl_waiting_note))
                CircularProgressIndicator()
            }
            is LinkWaitingUiState.Connected -> {
                Headline(stringResource(R.string.feature_guardian_impl_connected_title, name), announce = true)
                Body(stringResource(R.string.feature_guardian_impl_connected_body, name))
            }
            is LinkWaitingUiState.Declined -> {
                Headline(stringResource(R.string.feature_guardian_impl_declined_title, name), announce = true)
                Body(stringResource(R.string.feature_guardian_impl_declined_body))
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun WaitingPreview() {
    PhoneDoctorTheme { LinkWaitingScreen(LinkWaitingUiState.Waiting("엄마"), {}, {}) }
}
