package io.github.inq_o.phonedoctor.feature.onboarding.impl

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.inq_o.phonedoctor.core.designsystem.component.PdOutlinedButton
import io.github.inq_o.phonedoctor.core.designsystem.component.PdPrimaryButton
import io.github.inq_o.phonedoctor.core.designsystem.component.SeniorScreen
import io.github.inq_o.phonedoctor.core.designsystem.component.StatusCircle
import io.github.inq_o.phonedoctor.core.designsystem.component.StatusKind
import io.github.inq_o.phonedoctor.core.designsystem.theme.PhoneDoctorTheme

@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    LaunchedEffect(state.finished) {
        if (state.finished) onFinished()
    }
    // 허용하든 거부하든 다음 단계로 간다. 거부해도 홈 화면에는 그대로 보인다
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.next() }
    OnboardingScreen(
        state = state,
        onAgree = viewModel::next,
        onAllowNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.next()
            }
        },
        onOpenUsageSettings = { context.startFirst(viewModel.usageAccessSettingsIntents) },
        onNext = viewModel::next,
        modifier = modifier,
    )
}

/** 기기마다 열리는 설정 화면이 달라 순서대로 시도한다 */
private fun Context.startFirst(intents: List<Intent>) {
    for (intent in intents) {
        try {
            startActivity(intent)
            return
        } catch (e: ActivityNotFoundException) {
            continue
        }
    }
}

@Composable
internal fun OnboardingScreen(
    state: OnboardingUiState,
    onAgree: () -> Unit,
    onAllowNotifications: () -> Unit,
    onOpenUsageSettings: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 첫 설정 중에는 뒤로 가기로 앱이 닫히지 않게 한다 (앞 단계로도 돌아가지 않음: 고지는 이미 읽음)
    BackHandler(enabled = state.index > 0) {}
    SeniorScreen(
        modifier = modifier,
        actions = {
            when (state.step) {
                OnboardingStep.DISCLOSURE ->
                    PdPrimaryButton(stringResource(R.string.feature_onboarding_impl_disclosure_agree), onAgree)
                OnboardingStep.NOTIFICATION -> {
                    PdOutlinedButton(stringResource(R.string.feature_onboarding_impl_later), onNext)
                    PdPrimaryButton(stringResource(R.string.feature_onboarding_impl_notification_allow), onAllowNotifications)
                }
                OnboardingStep.USAGE_ACCESS ->
                    if (state.usageAccessGranted) {
                        PdPrimaryButton(stringResource(R.string.feature_onboarding_impl_start), onNext)
                    } else {
                        PdOutlinedButton(stringResource(R.string.feature_onboarding_impl_later), onNext)
                        PdPrimaryButton(stringResource(R.string.feature_onboarding_impl_usage_open), onOpenUsageSettings)
                    }
            }
        },
    ) {
        Text(
            text = stringResource(R.string.feature_onboarding_impl_step, state.steps.size, state.index + 1),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
        when (state.step) {
            OnboardingStep.DISCLOSURE -> {
                Title(stringResource(R.string.feature_onboarding_impl_disclosure_title))
                Body(stringResource(R.string.feature_onboarding_impl_disclosure_body_1))
                Body(stringResource(R.string.feature_onboarding_impl_disclosure_body_2))
                Body(stringResource(R.string.feature_onboarding_impl_disclosure_body_3))
            }
            OnboardingStep.NOTIFICATION -> {
                Title(stringResource(R.string.feature_onboarding_impl_notification_title))
                Body(stringResource(R.string.feature_onboarding_impl_notification_body))
            }
            OnboardingStep.USAGE_ACCESS ->
                if (state.usageAccessGranted) {
                    StatusCircle(StatusKind.OK)
                    Spacer(Modifier.height(24.dp))
                    Title(stringResource(R.string.feature_onboarding_impl_usage_granted_title))
                    Body(stringResource(R.string.feature_onboarding_impl_usage_granted_body))
                } else {
                    Title(stringResource(R.string.feature_onboarding_impl_usage_title))
                    Body(stringResource(R.string.feature_onboarding_impl_usage_body))
                    Body(stringResource(R.string.feature_onboarding_impl_usage_how))
                }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.fillMaxWidth().semantics { heading() })
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(16.dp))
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun DisclosurePreview() {
    PhoneDoctorTheme {
        OnboardingScreen(OnboardingUiState(OnboardingStep.entries), onAgree = {}, onAllowNotifications = {}, onOpenUsageSettings = {}, onNext = {})
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720, fontScale = 2f)
@Composable
private fun UsageAccessLargeFontPreview() {
    PhoneDoctorTheme {
        OnboardingScreen(OnboardingUiState(OnboardingStep.entries, index = 2), onAgree = {}, onAllowNotifications = {}, onOpenUsageSettings = {}, onNext = {})
    }
}
