package io.github.inq_o.phonedoctor.feature.guardian.impl.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import io.github.inq_o.phonedoctor.feature.guardian.api.navigation.AlertDetailNavKey
import io.github.inq_o.phonedoctor.feature.guardian.api.navigation.AlertHistoryNavKey
import io.github.inq_o.phonedoctor.feature.guardian.api.navigation.EnterCodeNavKey
import io.github.inq_o.phonedoctor.feature.guardian.api.navigation.GuardianHomeNavKey
import io.github.inq_o.phonedoctor.feature.guardian.api.navigation.GuardianSignInNavKey
import io.github.inq_o.phonedoctor.feature.guardian.api.navigation.LinkWaitingNavKey
import io.github.inq_o.phonedoctor.feature.guardian.impl.AlertDetailScreen
import io.github.inq_o.phonedoctor.feature.guardian.impl.AlertHistoryScreen
import io.github.inq_o.phonedoctor.feature.guardian.impl.EnterCodeScreen
import io.github.inq_o.phonedoctor.feature.guardian.impl.GuardianHomeScreen
import io.github.inq_o.phonedoctor.feature.guardian.impl.GuardianSignInScreen
import io.github.inq_o.phonedoctor.feature.guardian.impl.LinkWaitingScreen

/**
 * @param onSignedIn O-G1을 마침 → 홈 위에 코드 입력
 * @param canLeaveSignIn O-G1에 [돌아가기]를 보일지 (밑에 화면이 있을 때만)
 * @param onSignInBack O-G1 [돌아가기]
 * @param onAddFamily G1 [가족 연결하기] → O-G2
 * @param onRequested O-G2에서 연결 요청을 보냄 → O-G3 (코드 입력은 닫는다)
 * @param onSignInRequired O-G2에서 로그인이 풀린 것을 알게 됨 → O-G1
 * @param onHome 쌓인 화면을 닫고 G1으로
 * @param onRetry O-G3에서 시니어가 거절함 → 새 코드 입력
 * @param onOpenRequest G1·G3의 요청 → G2
 * @param onHistory G1 [지난 요청 보기] → G3
 * @param onBack G2·G3에서 뒤로
 */
fun EntryProviderScope<NavKey>.guardianEntries(
    onSignedIn: () -> Unit,
    canLeaveSignIn: () -> Boolean,
    onSignInBack: () -> Unit,
    onAddFamily: () -> Unit,
    onRequested: (linkId: String, seniorName: String) -> Unit,
    onSignInRequired: () -> Unit,
    onHome: () -> Unit,
    onRetry: () -> Unit,
    onOpenRequest: (String) -> Unit,
    onHistory: () -> Unit,
    onBack: () -> Unit,
) {
    entry<GuardianSignInNavKey> {
        GuardianSignInScreen(onSignedIn = onSignedIn, onBack = onSignInBack.takeIf { canLeaveSignIn() })
    }
    entry<GuardianHomeNavKey> {
        GuardianHomeScreen(onAddFamily = onAddFamily, onOpenRequest = onOpenRequest, onHistory = onHistory)
    }
    entry<EnterCodeNavKey> {
        EnterCodeScreen(onRequested = onRequested, onSignInRequired = onSignInRequired, onHome = onHome)
    }
    entry<AlertDetailNavKey> { key ->
        AlertDetailScreen(alertId = key.alertId, onBack = onBack)
    }
    entry<AlertHistoryNavKey> {
        AlertHistoryScreen(onOpen = onOpenRequest, onBack = onBack)
    }
    entry<LinkWaitingNavKey> { key ->
        LinkWaitingScreen(linkId = key.linkId, seniorName = key.seniorName, onHome = onHome, onRetry = onRetry)
    }
}
