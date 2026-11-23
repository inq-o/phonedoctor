package io.github.inq_o.phonedoctor.feature.guardian.impl.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import io.github.inq_o.phonedoctor.feature.guardian.api.navigation.EnterCodeNavKey
import io.github.inq_o.phonedoctor.feature.guardian.api.navigation.GuardianHomeNavKey
import io.github.inq_o.phonedoctor.feature.guardian.api.navigation.GuardianSignInNavKey
import io.github.inq_o.phonedoctor.feature.guardian.api.navigation.LinkWaitingNavKey
import io.github.inq_o.phonedoctor.feature.guardian.impl.EnterCodeScreen
import io.github.inq_o.phonedoctor.feature.guardian.impl.GuardianHomeScreen
import io.github.inq_o.phonedoctor.feature.guardian.impl.GuardianSignInScreen
import io.github.inq_o.phonedoctor.feature.guardian.impl.LinkWaitingScreen

/**
 * @param onSignedIn O-G1을 마침 → 홈 위에 코드 입력
 * @param onSignInBack O-G1에서 역할 선택으로 돌아가기
 * @param onAddFamily G1 [가족 연결하기] → O-G2
 * @param onRequested O-G2에서 연결 요청을 보냄 → O-G3 (코드 입력은 닫는다)
 * @param onSignInRequired O-G2에서 로그인이 풀린 것을 알게 됨 → O-G1
 * @param onHome 쌓인 화면을 닫고 G1으로
 * @param onRetry O-G3에서 시니어가 거절함 → 새 코드 입력
 */
fun EntryProviderScope<NavKey>.guardianEntries(
    onSignedIn: () -> Unit,
    onSignInBack: () -> Unit,
    onAddFamily: () -> Unit,
    onRequested: (linkId: String, seniorName: String) -> Unit,
    onSignInRequired: () -> Unit,
    onHome: () -> Unit,
    onRetry: () -> Unit,
) {
    entry<GuardianSignInNavKey> {
        GuardianSignInScreen(onSignedIn = onSignedIn, onBack = onSignInBack)
    }
    entry<GuardianHomeNavKey> {
        GuardianHomeScreen(onAddFamily = onAddFamily)
    }
    entry<EnterCodeNavKey> {
        EnterCodeScreen(onRequested = onRequested, onSignInRequired = onSignInRequired, onHome = onHome)
    }
    entry<LinkWaitingNavKey> { key ->
        LinkWaitingScreen(linkId = key.linkId, seniorName = key.seniorName, onHome = onHome, onRetry = onRetry)
    }
}
