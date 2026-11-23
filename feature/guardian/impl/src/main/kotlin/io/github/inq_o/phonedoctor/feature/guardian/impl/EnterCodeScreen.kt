package io.github.inq_o.phonedoctor.feature.guardian.impl

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianOutlinedButton
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianPrimaryButton
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianScreen
import io.github.inq_o.phonedoctor.core.designsystem.theme.PhoneDoctorTheme

/**
 * @param onRequested 연결 요청을 보냄 → O-G3 (linkId, 시니어 이름)
 * @param onSignInRequired 로그인이 풀려 있음 → O-G1
 * @param onHome 이미 연결된 가족이면 홈으로
 */
@Composable
fun EnterCodeScreen(
    onRequested: (linkId: String, seniorName: String) -> Unit,
    onSignInRequired: () -> Unit,
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EnterCodeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.requested) {
        state.requested?.let {
            viewModel.consumeRequested()
            onRequested(it.linkId, it.seniorName)
        }
    }
    LaunchedEffect(state.signInRequired) {
        if (state.signInRequired) {
            viewModel.consumeSignInRequired()
            onSignInRequired()
        }
    }
    EnterCodeScreen(
        state = state,
        onCodeChange = viewModel::onCodeChange,
        onSubmit = viewModel::submit,
        onScan = {
            // Google 코드 스캐너: Play 서비스가 카메라 화면을 띄우고 결과만 돌려준다 (이 앱은 카메라 권한이 없다)
            val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            GmsBarcodeScanning.getClient(context, options).startScan()
                .addOnSuccessListener { viewModel.onScanned(it.rawValue) }
                .addOnFailureListener { viewModel.onScanFailed() }
        },
        onHome = onHome,
        modifier = modifier,
    )
}

@Composable
internal fun EnterCodeScreen(
    state: EnterCodeUiState,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onScan: () -> Unit,
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    GuardianScreen(
        title = stringResource(R.string.feature_guardian_impl_code_title),
        modifier = modifier,
        actions = {
            if (state.error == CodeError.ALREADY_LINKED || state.error == CodeError.USED) {
                GuardianPrimaryButton(stringResource(R.string.feature_guardian_impl_home), onHome)
            } else {
                GuardianOutlinedButton(stringResource(R.string.feature_guardian_impl_code_scan), onScan, enabled = !state.busy)
                GuardianPrimaryButton(stringResource(R.string.feature_guardian_impl_code_submit), onSubmit, enabled = state.canSubmit)
            }
        },
    ) {
        Body(stringResource(R.string.feature_guardian_impl_code_body))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.code,
            onValueChange = onCodeChange,
            label = { Text(stringResource(R.string.feature_guardian_impl_code_label)) },
            singleLine = true,
            enabled = !state.busy,
            isError = state.error != null,
            textStyle = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 6.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        Spacer(Modifier.height(16.dp))
        state.error?.let { ErrorText(it.message(state.retryAfterSeconds)) }
        if (state.busy) CircularProgressIndicator()
    }
}

@Composable
private fun CodeError.message(retryAfterSeconds: Long?): String = when (this) {
    CodeError.NOT_FOUND -> stringResource(R.string.feature_guardian_impl_code_not_found)
    CodeError.EXPIRED -> stringResource(R.string.feature_guardian_impl_code_expired)
    CodeError.USED -> stringResource(R.string.feature_guardian_impl_code_used)
    CodeError.ALREADY_LINKED -> stringResource(R.string.feature_guardian_impl_code_already_linked)
    CodeError.LIMIT -> stringResource(R.string.feature_guardian_impl_code_limit)
    CodeError.TOO_MANY -> if (retryAfterSeconds != null) {
        stringResource(R.string.feature_guardian_impl_code_too_many_wait, retryAfterSeconds)
    } else {
        stringResource(R.string.feature_guardian_impl_code_too_many)
    }
    CodeError.NOT_PAIRING_QR -> stringResource(R.string.feature_guardian_impl_code_not_pairing_qr)
    CodeError.SCAN_FAILED -> stringResource(R.string.feature_guardian_impl_code_scan_failed)
    CodeError.NETWORK -> GuardianError.NETWORK.message()
    CodeError.BUSY -> GuardianError.BUSY.message()
    CodeError.WRONG_ROLE -> GuardianError.WRONG_ROLE.message()
    CodeError.UNKNOWN -> GuardianError.UNKNOWN.message()
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun EnterCodePreview() {
    PhoneDoctorTheme { EnterCodeScreen(EnterCodeUiState(code = "4829", error = CodeError.EXPIRED), {}, {}, {}, {}) }
}
