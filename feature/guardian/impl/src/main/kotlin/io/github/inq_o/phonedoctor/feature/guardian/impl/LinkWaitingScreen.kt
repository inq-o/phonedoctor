package io.github.inq_o.phonedoctor.feature.guardian.impl

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianOutlinedButton
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianPrimaryButton
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianScreen
import io.github.inq_o.phonedoctor.core.designsystem.component.PdButtonPair
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
    LinkWaitingScreen(
        state = state,
        onHome = onHome,
        onRetry = onRetry,
        onSaveName = { name -> viewModel.saveName(name, onHome) },
        modifier = modifier,
    )
}

@Composable
internal fun LinkWaitingScreen(
    state: LinkWaitingUiState,
    onHome: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onSaveName: (String?) -> Unit = {},
) {
    // 연결 전이라 보호자가 고른 이름은 아직 없다: 상대가 정한 이름, 없으면 "상대 폰"
    val name = phoneName(null, state.seniorName, unnamed = R.string.feature_guardian_impl_phone_other)
    var choice by rememberSaveable(stateSaver = PhoneNameChoiceSaver) { mutableStateOf(PhoneNameChoice()) }
    GuardianScreen(
        title = null,
        modifier = modifier,
        actions = {
            when (state) {
                is LinkWaitingUiState.Waiting -> GuardianOutlinedButton(stringResource(R.string.feature_guardian_impl_waiting_later), onHome)
                // O-G4: [건너뛰기 | 저장] (보호자 화면: 왼쪽 보조, 오른쪽 주요)
                is LinkWaitingUiState.Connected -> PdButtonPair(
                    first = { GuardianOutlinedButton(stringResource(R.string.feature_guardian_impl_phone_name_skip), { onSaveName(null) }, it) },
                    second = {
                        // 고르지 않았거나 직접 입력이 비었으면 저장할 것이 없다 ([건너뛰기]와 구분)
                        GuardianPrimaryButton(stringResource(R.string.feature_guardian_impl_phone_name_save), { onSaveName(choice.value) }, it, enabled = choice.value != null)
                    },
                    minHeight = 48.dp,
                    gap = 8.dp,
                )
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
                Body(stringResource(R.string.feature_guardian_impl_phone_name_body))
                PhoneNamePicker(choice, onChange = { choice = it })
                Body(stringResource(R.string.feature_guardian_impl_phone_name_skip_note, phoneName(null, state.seniorName)))
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

/** 화면을 돌려도 고른 것이 남게 */
private val PhoneNameChoiceSaver = listSaver<PhoneNameChoice, Any?>(
    save = { listOf(it.preset, it.custom, it.text) },
    restore = { PhoneNameChoice(it[0] as String?, it[1] as Boolean, it[2] as String) },
)

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ConnectedPreview() {
    PhoneDoctorTheme { LinkWaitingScreen(LinkWaitingUiState.Connected("김영희"), {}, {}) }
}
