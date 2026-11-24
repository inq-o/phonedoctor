package io.github.inq_o.phonedoctor.feature.seniorfamily.impl

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.inq_o.phonedoctor.core.designsystem.component.PdButtonPair
import io.github.inq_o.phonedoctor.core.designsystem.component.PdOutlinedButton
import io.github.inq_o.phonedoctor.core.designsystem.component.PdPrimaryButton
import io.github.inq_o.phonedoctor.core.designsystem.component.SeniorScreen
import io.github.inq_o.phonedoctor.core.designsystem.theme.PhoneDoctorTheme
import io.github.inq_o.phonedoctor.core.model.PairingCode
import java.time.Instant

@Composable
fun PairingCodeScreen(
    onLinkRequested: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PairingCodeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val requested by viewModel.linkRequested.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.startWatching()
        onPauseOrDispose { viewModel.stopWatching() }
    }
    LaunchedEffect(requested) {
        requested?.let {
            viewModel.onLinkOpened()
            onLinkRequested(it)
        }
    }
    PairingCodeScreen(state = state, onNewCode = viewModel::newCode, onBack = onBack, modifier = modifier)
}

/**
 * O-S4 연결 코드·QR. 주요 행동은 가족 폰에서 하므로 이 화면의 채운 버튼은 실패·만료 때의 "다시"뿐이다.
 * 남은 시간은 세지 않는다 (ux-flows 원칙 6)
 */
@Composable
internal fun PairingCodeScreen(
    state: PairingCodeUiState,
    onNewCode: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SeniorScreen(
        modifier = modifier,
        actions = {
            // 버튼 쌍 (ux-flows): 왼쪽 [나중에]는 기다리는 화면에서 빠져나가는 길이라 늘 둔다 (원칙 1 예외)
            val later: @Composable (Modifier) -> Unit = { PdOutlinedButton(stringResource(R.string.feature_senior_family_impl_later), onBack, it) }
            when (state) {
                is PairingCodeUiState.Failed -> PdButtonPair(
                    first = later,
                    second = { PdPrimaryButton(stringResource(R.string.feature_senior_family_impl_retry), onNewCode, it) },
                )
                // 가족 폰에서 "코드 시간이 지났어요"가 뜨면 여기서 바로 새로 받는다 (이 폰 시계로는 아직 안 지났어도)
                is PairingCodeUiState.Ready -> PdButtonPair(
                    first = later,
                    second = {
                        if (state.expired) {
                            PdPrimaryButton(stringResource(R.string.feature_senior_family_impl_code_new), onNewCode, it)
                        } else {
                            PdOutlinedButton(stringResource(R.string.feature_senior_family_impl_code_new), onNewCode, it)
                        }
                    },
                )
                PairingCodeUiState.Loading -> later(Modifier)
            }
        },
    ) {
        when (state) {
            PairingCodeUiState.Loading -> {
                Headline(stringResource(R.string.feature_senior_family_impl_code_loading), announce = true)
                CircularProgressIndicator()
            }
            is PairingCodeUiState.Failed -> {
                Headline(stringResource(R.string.feature_senior_family_impl_code_failed_title), announce = true)
                Body(state.error.message())
            }
            is PairingCodeUiState.Ready -> if (state.expired) {
                Headline(stringResource(R.string.feature_senior_family_impl_code_expired_title), announce = true)
                Body(stringResource(R.string.feature_senior_family_impl_code_expired_body))
            } else {
                Headline(stringResource(R.string.feature_senior_family_impl_code_title), announce = true)
                Body(stringResource(R.string.feature_senior_family_impl_code_how))
                CodeDigits(state.code.code)
                Spacer(Modifier.height(16.dp))
                QrCode(state.code.qrPayload, stringResource(R.string.feature_senior_family_impl_code_qr_description), size = 220.dp)
                Spacer(Modifier.height(16.dp))
                Body(stringResource(R.string.feature_senior_family_impl_code_valid))
                Body(stringResource(R.string.feature_senior_family_impl_code_waiting))
            }
        }
    }
}

/** "482 913"처럼 세 자리씩. 화면 낭독은 한 자리씩 읽는다 */
@Composable
private fun CodeDigits(code: String) {
    Text(
        text = code.chunked(3).joinToString(" "),
        style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 4.sp),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = code.toList().joinToString(" ") },
    )
}

private val PreviewCode = PairingCode("482913", "phonedoctor://pair?c=482913", Instant.now().plusSeconds(600))

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ReadyPreview() {
    PhoneDoctorTheme { PairingCodeScreen(PairingCodeUiState.Ready(PreviewCode), {}, {}) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720, fontScale = 2f)
@Composable
private fun ReadyLargeFontPreview() {
    PhoneDoctorTheme { PairingCodeScreen(PairingCodeUiState.Ready(PreviewCode), {}, {}) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun FailedPreview() {
    PhoneDoctorTheme { PairingCodeScreen(PairingCodeUiState.Failed(FamilyError.NETWORK), {}, {}) }
}
