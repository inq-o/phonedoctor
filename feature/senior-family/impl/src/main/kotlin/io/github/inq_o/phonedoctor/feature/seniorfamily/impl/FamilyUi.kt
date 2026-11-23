package io.github.inq_o.phonedoctor.feature.seniorfamily.impl

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.inq_o.phonedoctor.core.data.RoleMismatchException
import io.github.inq_o.phonedoctor.core.network.ApiException
import java.io.IOException

/** 화면이 안내할 실패 종류 */
enum class FamilyError { NETWORK, BUSY, WRONG_ROLE, UNKNOWN }

internal fun Throwable.toFamilyError(): FamilyError = when (this) {
    is IOException -> FamilyError.NETWORK
    is ApiException -> if (status == 429 || status >= 500) FamilyError.BUSY else FamilyError.UNKNOWN
    is RoleMismatchException -> FamilyError.WRONG_ROLE
    else -> FamilyError.UNKNOWN
}

@Composable
internal fun FamilyError.message(): String = stringResource(
    when (this) {
        FamilyError.NETWORK -> R.string.feature_senior_family_impl_error_network
        FamilyError.BUSY -> R.string.feature_senior_family_impl_error_busy
        FamilyError.WRONG_ROLE -> R.string.feature_senior_family_impl_error_role
        FamilyError.UNKNOWN -> R.string.feature_senior_family_impl_error_unknown
    },
)

/** 표시 이름이 비었으면 "가족" */
@Composable
internal fun displayName(name: String): String =
    name.trim().ifEmpty { stringResource(R.string.feature_senior_family_impl_fallback_name) }

/** 상태 문장 (28sp). [announce]면 바뀔 때 화면 낭독이 읽어 준다 */
@Composable
internal fun Headline(text: String, announce: Boolean = false) {
    Spacer(Modifier.height(16.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().semantics {
            heading()
            if (announce) liveRegion = LiveRegionMode.Polite
        },
    )
    Spacer(Modifier.height(16.dp))
}

@Composable
internal fun Body(text: String, center: Boolean = true) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
}

/** 실패 안내. 빨간 글자는 큰 글씨(titleLarge)로만 쓴다(본문 대비 7:1 미달). 바뀌면 화면 낭독이 읽어 준다 */
@Composable
internal fun ErrorText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    )
    Spacer(Modifier.height(12.dp))
}
