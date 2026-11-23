package io.github.inq_o.phonedoctor.feature.seniorfamily.impl

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.inq_o.phonedoctor.core.designsystem.component.PdOutlinedButton
import io.github.inq_o.phonedoctor.core.designsystem.component.PdPrimaryButton
import io.github.inq_o.phonedoctor.core.designsystem.component.SeniorScreen
import io.github.inq_o.phonedoctor.core.designsystem.component.StatusCircle
import io.github.inq_o.phonedoctor.core.designsystem.component.StatusKind
import io.github.inq_o.phonedoctor.core.designsystem.theme.PhoneDoctorTheme
import io.github.inq_o.phonedoctor.core.model.FamilyLink
import io.github.inq_o.phonedoctor.core.model.LinkStatus
import java.time.Instant

@Composable
fun LinkConfirmScreen(
    linkId: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LinkConfirmViewModel = hiltViewModel<LinkConfirmViewModel, LinkConfirmViewModel.Factory>(
        key = "link-$linkId",
        creationCallback = { it.create(linkId) },
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LinkConfirmScreen(
        state = state,
        onConfirm = viewModel::confirm,
        onDecline = viewModel::decline,
        onRetry = viewModel::load,
        onDone = onDone,
        modifier = modifier,
    )
}

/** S3 "○○님과 연결할까요?" */
@Composable
internal fun LinkConfirmScreen(
    state: LinkConfirmUiState,
    onConfirm: () -> Unit,
    onDecline: () -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SeniorScreen(
        modifier = modifier,
        actions = {
            when (state) {
                LinkConfirmUiState.Loading -> Unit
                is LinkConfirmUiState.LoadFailed -> {
                    PdOutlinedButton(stringResource(R.string.feature_senior_family_impl_home), onDone)
                    PdPrimaryButton(stringResource(R.string.feature_senior_family_impl_retry), onRetry)
                }
                is LinkConfirmUiState.Ready -> {
                    PdOutlinedButton(stringResource(R.string.feature_senior_family_impl_confirm_no), onDecline, enabled = !state.busy)
                    if (state.limitReached) {
                        PdPrimaryButton(stringResource(R.string.feature_senior_family_impl_home), onDone, enabled = !state.busy)
                    } else {
                        PdPrimaryButton(stringResource(R.string.feature_senior_family_impl_confirm_yes), onConfirm, enabled = !state.busy)
                    }
                }
                else -> PdPrimaryButton(stringResource(R.string.feature_senior_family_impl_home), onDone)
            }
        },
    ) {
        Spacer(Modifier.height(8.dp))
        when (state) {
            LinkConfirmUiState.Loading -> CircularProgressIndicator()
            is LinkConfirmUiState.LoadFailed -> {
                Headline(stringResource(R.string.feature_senior_family_impl_confirm_load_failed), announce = true)
                Body(state.error.message())
            }
            is LinkConfirmUiState.Ready -> if (state.limitReached) {
                Headline(stringResource(R.string.feature_senior_family_impl_limit_title), announce = true)
                Body(stringResource(R.string.feature_senior_family_impl_limit_body, displayName(state.link.guardianName)))
                state.error?.let { ErrorText(it.message()) }
                if (state.busy) CircularProgressIndicator()
            } else {
                val name = displayName(state.link.guardianName)
                Headline(stringResource(R.string.feature_senior_family_impl_confirm_title, name))
                Body(stringResource(R.string.feature_senior_family_impl_confirm_body, name))
                state.error?.let { ErrorText(it.message()) }
                if (state.busy) CircularProgressIndicator()
            }
            is LinkConfirmUiState.Connected -> {
                val name = displayName(state.guardianName)
                StatusCircle(StatusKind.OK)
                Headline(stringResource(R.string.feature_senior_family_impl_connected_title, name), announce = true)
                Body(stringResource(R.string.feature_senior_family_impl_connected_body, name))
            }
            is LinkConfirmUiState.Declined -> {
                Headline(stringResource(R.string.feature_senior_family_impl_declined_title), announce = true)
                Body(stringResource(R.string.feature_senior_family_impl_declined_body))
            }
            LinkConfirmUiState.Gone -> {
                Headline(stringResource(R.string.feature_senior_family_impl_gone_title), announce = true)
                Body(stringResource(R.string.feature_senior_family_impl_gone_body))
            }
        }
    }
}

private val PreviewLink = FamilyLink("l1", LinkStatus.PENDING, "엄마", "민지", Instant.now(), null, null)

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ReadyPreview() {
    PhoneDoctorTheme { LinkConfirmScreen(LinkConfirmUiState.Ready(PreviewLink), {}, {}, {}, {}) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720, fontScale = 2f)
@Composable
private fun ReadyLargeFontPreview() {
    PhoneDoctorTheme { LinkConfirmScreen(LinkConfirmUiState.Ready(PreviewLink, error = FamilyError.NETWORK), {}, {}, {}, {}) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ConnectedPreview() {
    PhoneDoctorTheme { LinkConfirmScreen(LinkConfirmUiState.Connected("민지"), {}, {}, {}, {}) }
}
