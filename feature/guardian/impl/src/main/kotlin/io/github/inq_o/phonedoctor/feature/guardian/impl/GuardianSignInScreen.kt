package io.github.inq_o.phonedoctor.feature.guardian.impl

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianOutlinedButton
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianPrimaryButton
import io.github.inq_o.phonedoctor.core.designsystem.component.GuardianScreen
import io.github.inq_o.phonedoctor.core.designsystem.theme.PhoneDoctorTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * @param onSignedIn 로그인과 알림 권한 묻기를 마침
 * @param onBack 역할 선택(또는 홈)으로 돌아가기. 돌아갈 곳이 없으면 null
 */
@Composable
fun GuardianSignInScreen(
    onSignedIn: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    viewModel: GuardianSignInViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 로그인하는 동안 뒤로 가면 화면은 닫혀도 Firebase 로그인은 끝나, 알림 권한·코드 입력을 건너뛴 채 보호자가 된다
    BackHandler(enabled = state.busy) {}
    val scope = rememberCoroutineScope()
    // 허용하든 거부하든 넘어간다. 꺼져 있으면 홈에서 다시 안내한다
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onSignedIn() }
    LaunchedEffect(state.signedIn) {
        if (!state.signedIn) return@LaunchedEffect
        viewModel.consumeSignedIn()
        // 연결되면(LINK_ACTIVATED) 알림으로 알려 주므로 연결 전에 묻는다
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else onSignedIn()
    }
    GuardianSignInScreen(
        state = state,
        onSignIn = {
            if (viewModel.start()) {
                scope.launch {
                    val token = try {
                        requestGoogleIdToken(context)
                    } catch (e: CancellationException) {
                        viewModel.onAbandoned()
                        throw e
                    } catch (e: Exception) {
                        viewModel.onFailed(e)
                        return@launch
                    }
                    viewModel.onToken(token)
                }
            }
        },
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
internal fun GuardianSignInScreen(
    state: SignInUiState,
    onSignIn: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    GuardianScreen(
        title = stringResource(R.string.feature_guardian_impl_sign_in_title),
        modifier = modifier,
        actions = {
            if (onBack != null) GuardianOutlinedButton(stringResource(R.string.feature_guardian_impl_back), onBack, enabled = !state.busy)
            GuardianPrimaryButton(stringResource(R.string.feature_guardian_impl_sign_in), onSignIn, enabled = !state.busy)
        },
    ) {
        state.error?.let { ErrorText(it.message()) }
        Body(stringResource(R.string.feature_guardian_impl_sign_in_body))
        Body(stringResource(R.string.feature_guardian_impl_sign_in_scope))
        if (state.busy) CircularProgressIndicator()
    }
}

@Composable
private fun SignInError.message(): String = stringResource(
    when (this) {
        SignInError.NO_ACCOUNT -> R.string.feature_guardian_impl_sign_in_no_account
        SignInError.UNAVAILABLE -> R.string.feature_guardian_impl_sign_in_unavailable
        SignInError.NETWORK -> R.string.feature_guardian_impl_error_network
        SignInError.WRONG_ROLE -> R.string.feature_guardian_impl_error_role
        SignInError.UNKNOWN -> R.string.feature_guardian_impl_sign_in_failed
    },
)

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun SignInPreview() {
    PhoneDoctorTheme { GuardianSignInScreen(SignInUiState(error = SignInError.NETWORK), {}, {}) }
}
