package io.github.inq_o.phonedoctor.feature.guardian.impl

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.inq_o.phonedoctor.core.data.PhoneNameRepository
import io.github.inq_o.phonedoctor.core.data.RoleMismatchException
import io.github.inq_o.phonedoctor.core.model.PhoneName
import io.github.inq_o.phonedoctor.core.network.ApiException
import io.github.inq_o.phonedoctor.core.network.NotSignedInException
import io.github.inq_o.phonedoctor.core.network.isAppCheckFailure
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** 화면이 안내할 실패 종류 (목록·연결 끊기) */
enum class GuardianError { NETWORK, APP_CHECK, BUSY, WRONG_ROLE, SIGNED_OUT, UNKNOWN }

// 망 문제가 아닌 App Check 실패는 "인터넷 없음"과 따로 안내한다 (IOException이라 먼저 본다)
internal fun Throwable.toGuardianError(): GuardianError = if (isAppCheckFailure()) GuardianError.APP_CHECK else when (this) {
    // Firebase 사용자가 없음 (등록한 뒤 사용자가 지워지거나 막힘). IOException이라 먼저 본다
    is NotSignedInException -> GuardianError.SIGNED_OUT
    is IOException -> GuardianError.NETWORK
    is ApiException -> when {
        status == 429 || status >= 500 -> GuardianError.BUSY
        isRoleMismatch() -> GuardianError.WRONG_ROLE
        else -> GuardianError.UNKNOWN
    }
    is RoleMismatchException -> GuardianError.WRONG_ROLE
    else -> GuardianError.UNKNOWN
}

/** 이 Firebase 사용자가 서버에 다른 역할로 등록돼 있음 (POST /devices 409) */
internal fun ApiException.isRoleMismatch() = status == 409 && slug == "role-mismatch"

@Composable
internal fun GuardianError.message(): String = stringResource(
    when (this) {
        GuardianError.NETWORK -> R.string.feature_guardian_impl_error_network
        GuardianError.APP_CHECK -> R.string.feature_guardian_impl_error_app_check
        GuardianError.BUSY -> R.string.feature_guardian_impl_error_busy
        GuardianError.WRONG_ROLE -> R.string.feature_guardian_impl_error_role
        GuardianError.SIGNED_OUT -> R.string.feature_guardian_impl_error_signed_out
        GuardianError.UNKNOWN -> R.string.feature_guardian_impl_error_unknown
    },
)

/** 보호자가 이 폰에 정해 둔 상대 폰 표시 이름 (연결 ID → 이름). 보호자 화면 묶음([ProvidePhoneNames])이 채운다 */
internal val LocalChosenPhoneNames = staticCompositionLocalOf<Map<String, String>> { emptyMap() }

/**
 * 요청의 [linkIds]는 그 요청을 받은 모든 보호자의 연결이다 (서버 alertViews). 이름 맵에는 이 보호자의 연결만 있으므로
 * 목록에서 이름이 있는 연결을 찾는다 (첫 ID가 다른 보호자의 연결일 수 있음)
 */
internal fun chosenNameFor(linkIds: List<String>, names: Map<String, String>): String? = linkIds.firstNotNullOfOrNull { names[it] }

/** 요청 화면용: [linkIds] 중 이 보호자가 이름을 정한 연결의 이름 → 상대가 정한 이름 → "연결한 폰" */
@Composable
internal fun phoneName(linkIds: List<String>, own: String): String =
    phoneLabel(PhoneName.of(chosenNameFor(linkIds, LocalChosenPhoneNames.current), own), R.string.feature_guardian_impl_phone_unnamed)

/**
 * 상대 폰 이름 (ux-flows §2): "엄마 폰"(보호자가 고른 이름) / "김영희님 폰"(상대가 정한 이름) / "연결한 폰".
 * 역할 이름을 대신 넣지 않는다. 연결 전처럼 이름을 모를 때는 [unnamed]("상대 폰")
 */
@Composable
internal fun phoneName(linkId: String?, own: String, @StringRes unnamed: Int = R.string.feature_guardian_impl_phone_unnamed): String =
    phoneLabel(PhoneName.of(linkId?.let { LocalChosenPhoneNames.current[it] }, own), unnamed)

@Composable
private fun phoneLabel(name: PhoneName, @StringRes unnamed: Int): String = when (name) {
    is PhoneName.Chosen -> stringResource(R.string.feature_guardian_impl_phone_chosen, name.name)
    is PhoneName.Own -> stringResource(R.string.feature_guardian_impl_phone_own, name.name)
    PhoneName.Unnamed -> stringResource(unnamed)
}

/** 보호자 화면마다 감싸 표시 이름을 내려 준다 */
@Composable
fun ProvidePhoneNames(viewModel: PhoneNamesViewModel = hiltViewModel(), content: @Composable () -> Unit) {
    // 불러오기 전에는 그리지 않는다: 빈 맵으로 그리면 "김영희님 폰" → "엄마 폰"으로 바뀌어 보이고 낭독도 틀린 이름을 읽는다
    val names by viewModel.names.collectAsStateWithLifecycle()
    names?.let { CompositionLocalProvider(LocalChosenPhoneNames provides it, content = content) }
}

@HiltViewModel
class PhoneNamesViewModel @Inject constructor(repository: PhoneNameRepository) : ViewModel() {
    val names: StateFlow<Map<String, String>?> =
        repository.chosenNames.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

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
