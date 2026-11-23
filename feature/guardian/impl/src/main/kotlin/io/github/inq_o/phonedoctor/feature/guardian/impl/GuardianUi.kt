package io.github.inq_o.phonedoctor.feature.guardian.impl

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
import androidx.compose.ui.unit.dp
import io.github.inq_o.phonedoctor.core.data.RoleMismatchException
import io.github.inq_o.phonedoctor.core.network.ApiException
import java.io.IOException

/** 화면이 안내할 실패 종류 (목록·연결 끊기) */
enum class GuardianError { NETWORK, BUSY, WRONG_ROLE, UNKNOWN }

internal fun Throwable.toGuardianError(): GuardianError = when (this) {
    is IOException -> GuardianError.NETWORK
    is ApiException -> if (status == 429 || status >= 500) GuardianError.BUSY else GuardianError.UNKNOWN
    is RoleMismatchException -> GuardianError.WRONG_ROLE
    else -> GuardianError.UNKNOWN
}

@Composable
internal fun GuardianError.message(): String = stringResource(
    when (this) {
        GuardianError.NETWORK -> R.string.feature_guardian_impl_error_network
        GuardianError.BUSY -> R.string.feature_guardian_impl_error_busy
        GuardianError.WRONG_ROLE -> R.string.feature_guardian_impl_error_role
        GuardianError.UNKNOWN -> R.string.feature_guardian_impl_error_unknown
    },
)

/**
 * 시니어 표시 이름. 비었으면 "부모님". 문장에서는 "님"을 붙이지 않고 "엄마 폰"처럼 쓴다
 * (서버의 기본 이름이 "부모님"이라 "부모님님"이 되지 않게)
 */
@Composable
internal fun seniorName(name: String): String =
    name.trim().ifEmpty { stringResource(R.string.feature_guardian_impl_senior_fallback) }

/** 상태 문장 (22sp). [announce]면 바뀔 때 화면 낭독이 읽어 준다 */
@Composable
internal fun Headline(text: String, announce: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.fillMaxWidth().semantics {
            heading()
            if (announce) liveRegion = LiveRegionMode.Polite
        },
    )
    Spacer(Modifier.height(12.dp))
}

@Composable
internal fun Body(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(12.dp))
}

/** 실패 안내. 바뀌면 화면 낭독이 읽어 준다 */
@Composable
internal fun ErrorText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    )
    Spacer(Modifier.height(12.dp))
}
