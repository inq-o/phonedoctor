package io.github.inq_o.phonedoctor.feature.onboarding.impl

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.inq_o.phonedoctor.core.designsystem.component.PdOutlinedButton
import io.github.inq_o.phonedoctor.core.designsystem.component.PdPrimaryButton
import io.github.inq_o.phonedoctor.core.designsystem.component.SeniorScreen
import io.github.inq_o.phonedoctor.core.designsystem.theme.PhoneDoctorTheme
import io.github.inq_o.phonedoctor.core.model.DeviceRole

@Composable
fun RoleChoiceScreen(
    onChosen: (DeviceRole) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RoleChoiceViewModel = hiltViewModel(),
) {
    val chosen by viewModel.chosen.collectAsStateWithLifecycle()
    LaunchedEffect(chosen) {
        chosen?.let {
            viewModel.consumeChosen()
            onChosen(it)
        }
    }
    RoleChoiceScreen(onChoose = viewModel::choose, modifier = modifier)
}

/**
 * O1. 설치를 돕는 사람이 고르는 경우가 많아 두 쓰임새를 문장으로 설명한다.
 * 시니어 폰이 더 흔하고 시니어가 볼 수 있는 화면이라 채운 버튼은 [이 폰을 지켜요] 하나 (ux-flows 원칙 1)
 */
@Composable
internal fun RoleChoiceScreen(onChoose: (DeviceRole) -> Unit, modifier: Modifier = Modifier) {
    SeniorScreen(
        modifier = modifier,
        actions = {
            PdOutlinedButton(stringResource(R.string.feature_onboarding_impl_role_guardian), { onChoose(DeviceRole.GUARDIAN) })
            PdPrimaryButton(stringResource(R.string.feature_onboarding_impl_role_senior), { onChoose(DeviceRole.SENIOR) })
        },
    ) {
        Text(
            stringResource(R.string.feature_onboarding_impl_role_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.fillMaxWidth().semantics { heading() },
        )
        Spacer(Modifier.height(24.dp))
        Choice(stringResource(R.string.feature_onboarding_impl_role_senior), stringResource(R.string.feature_onboarding_impl_role_senior_body))
        Choice(stringResource(R.string.feature_onboarding_impl_role_guardian), stringResource(R.string.feature_onboarding_impl_role_guardian_body))
    }
}

@Composable
private fun Choice(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    Text(body, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(24.dp))
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun RoleChoicePreview() {
    PhoneDoctorTheme { RoleChoiceScreen(onChoose = {}) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720, fontScale = 2f)
@Composable
private fun RoleChoiceLargeFontPreview() {
    PhoneDoctorTheme { RoleChoiceScreen(onChoose = {}) }
}
