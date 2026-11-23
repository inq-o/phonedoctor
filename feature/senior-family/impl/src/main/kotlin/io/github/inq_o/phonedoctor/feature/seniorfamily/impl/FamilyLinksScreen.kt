package io.github.inq_o.phonedoctor.feature.seniorfamily.impl

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.inq_o.phonedoctor.core.designsystem.component.PdOutlinedButton
import io.github.inq_o.phonedoctor.core.designsystem.component.PdPrimaryButton
import io.github.inq_o.phonedoctor.core.designsystem.component.SeniorScreen
import io.github.inq_o.phonedoctor.core.designsystem.theme.PhoneDoctorTheme
import io.github.inq_o.phonedoctor.core.model.FamilyLink
import io.github.inq_o.phonedoctor.core.model.LinkStatus
import java.time.Instant
import java.time.ZoneId

@Composable
fun FamilyLinksScreen(
    onAddFamily: () -> Unit,
    onAnswer: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FamilyLinksViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    FamilyLinksScreen(
        state = state,
        onAddFamily = onAddFamily,
        onAnswer = onAnswer,
        onRevoke = viewModel::revoke,
        onRetry = viewModel::refresh,
        onBack = onBack,
        modifier = modifier,
    )
}

/** S5 가족 연결 관리. 연결 끊기는 확인창을 거친다 (ux-flows F4) */
@Composable
internal fun FamilyLinksScreen(
    state: FamilyLinksUiState,
    onAddFamily: () -> Unit,
    onAnswer: (String) -> Unit,
    onRevoke: (FamilyLink) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirming by rememberSaveable { mutableStateOf<String?>(null) }
    val ready = state as? FamilyLinksUiState.Ready
    SeniorScreen(
        modifier = modifier,
        topBar = {
            Text(
                text = stringResource(R.string.feature_senior_family_impl_links_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp).semantics { heading() },
            )
        },
        actions = {
            PdOutlinedButton(stringResource(R.string.feature_senior_family_impl_home), onBack)
            when {
                state is FamilyLinksUiState.Failed ->
                    PdPrimaryButton(stringResource(R.string.feature_senior_family_impl_retry), onRetry)
                ready != null && ready.canAdd ->
                    PdPrimaryButton(stringResource(R.string.feature_senior_family_impl_links_add), onAddFamily, enabled = ready.revoking == null)
            }
        },
    ) {
        when (state) {
            FamilyLinksUiState.Loading -> CircularProgressIndicator()
            is FamilyLinksUiState.Failed -> {
                Headline(stringResource(R.string.feature_senior_family_impl_links_load_failed), announce = true)
                Body(state.error.message())
            }
            is FamilyLinksUiState.Ready -> {
                when (val message = state.message) {
                    is RevokeMessage.Revoked ->
                        Headline(stringResource(R.string.feature_senior_family_impl_links_revoked, displayName(message.name)), announce = true)
                    is RevokeMessage.Failed -> {
                        ErrorText(stringResource(R.string.feature_senior_family_impl_links_revoke_failed))
                        Body(message.error.message())
                    }
                    null -> Unit
                }
                if (state.links.isEmpty()) {
                    Body(stringResource(R.string.feature_senior_family_impl_links_empty))
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        state.links.forEach { link ->
                            LinkCard(
                                link = link,
                                enabled = state.revoking == null,
                                onAnswer = { onAnswer(link.linkId) },
                                onRevoke = { confirming = link.linkId },
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                Body(stringResource(R.string.feature_senior_family_impl_links_scope))
                if (!state.canAdd) Body(stringResource(R.string.feature_senior_family_impl_links_full))
                if (state.revoking != null) CircularProgressIndicator()
            }
        }
    }
    val target = ready?.links?.firstOrNull { it.linkId == confirming }
    if (target != null) {
        RevokeDialog(
            name = displayName(target.guardianName),
            onRevoke = {
                confirming = null
                onRevoke(target)
            },
            onKeep = { confirming = null },
        )
    }
}

@Composable
private fun LinkCard(link: FamilyLink, enabled: Boolean, onAnswer: () -> Unit, onRevoke: () -> Unit) {
    OutlinedCard(
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(displayName(link.guardianName), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            if (link.status == LinkStatus.PENDING) {
                Text(stringResource(R.string.feature_senior_family_impl_links_pending), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(12.dp))
                PdOutlinedButton(stringResource(R.string.feature_senior_family_impl_links_answer), onAnswer, enabled = enabled)
            } else {
                val since = (link.activatedAt ?: link.createdAt).atZone(ZoneId.systemDefault())
                Text(
                    stringResource(R.string.feature_senior_family_impl_links_since, since.year, since.monthValue, since.dayOfMonth),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(12.dp))
                PdOutlinedButton(stringResource(R.string.feature_senior_family_impl_links_revoke), onRevoke, enabled = enabled)
            }
        }
    }
}

/** "민지님과 연결을 끊을까요?" 바깥을 누르거나 뒤로 가면 그대로 둔다 (끊는 건 빨간 버튼으로만) */
@Composable
private fun RevokeDialog(name: String, onRevoke: () -> Unit, onKeep: () -> Unit) {
    Dialog(onDismissRequest = onKeep) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = stringResource(R.string.feature_senior_family_impl_revoke_dialog_title, name),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(R.string.feature_senior_family_impl_revoke_dialog_body, name), style = MaterialTheme.typography.bodyLarge)
                PdOutlinedButton(stringResource(R.string.feature_senior_family_impl_revoke_dialog_keep), onKeep)
                PdPrimaryButton(stringResource(R.string.feature_senior_family_impl_links_revoke), onRevoke, danger = true)
            }
        }
    }
}

private val PreviewLinks = listOf(
    FamilyLink("l1", LinkStatus.ACTIVE, "엄마", "민지", Instant.parse("2026-11-20T01:00:00Z"), Instant.parse("2026-11-20T01:02:00Z"), null),
    FamilyLink("l2", LinkStatus.PENDING, "엄마", "철수", Instant.now(), null, null),
)

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ReadyPreview() {
    PhoneDoctorTheme { FamilyLinksScreen(FamilyLinksUiState.Ready(PreviewLinks), {}, {}, {}, {}, {}) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720, fontScale = 2f)
@Composable
private fun EmptyLargeFontPreview() {
    PhoneDoctorTheme { FamilyLinksScreen(FamilyLinksUiState.Ready(emptyList()), {}, {}, {}, {}, {}) }
}
