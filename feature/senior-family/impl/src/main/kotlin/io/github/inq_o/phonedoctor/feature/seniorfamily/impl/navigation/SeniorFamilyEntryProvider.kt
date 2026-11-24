package io.github.inq_o.phonedoctor.feature.seniorfamily.impl.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import io.github.inq_o.phonedoctor.feature.seniorfamily.api.navigation.FamilyLinksNavKey
import io.github.inq_o.phonedoctor.feature.seniorfamily.api.navigation.LinkConfirmNavKey
import io.github.inq_o.phonedoctor.feature.seniorfamily.api.navigation.PairingCodeNavKey
import io.github.inq_o.phonedoctor.feature.seniorfamily.impl.FamilyLinksScreen
import io.github.inq_o.phonedoctor.feature.seniorfamily.impl.LinkConfirmScreen
import io.github.inq_o.phonedoctor.feature.seniorfamily.impl.PairingCodeScreen

/**
 * @param onAddFamily S5 → 연결 코드
 * @param onAnswer S5의 확인 대기 → S3
 * @param onLinkRequested 연결 코드 화면에서 보호자가 코드를 넣음 → S3 (코드 화면은 닫는다)
 * @param onHome [홈으로] 버튼. 쌓인 화면을 모두 닫고 홈으로
 * @param onBack 연결 코드 화면의 [나중에]. 연 곳(홈·S5)으로
 */
fun EntryProviderScope<NavKey>.seniorFamilyEntries(
    onAddFamily: () -> Unit,
    onAnswer: (String) -> Unit,
    onLinkRequested: (String) -> Unit,
    onHome: () -> Unit,
    onBack: () -> Unit,
) {
    entry<FamilyLinksNavKey> {
        FamilyLinksScreen(onAddFamily = onAddFamily, onAnswer = onAnswer, onBack = onHome)
    }
    entry<PairingCodeNavKey> {
        PairingCodeScreen(onLinkRequested = onLinkRequested, onBack = onBack)
    }
    entry<LinkConfirmNavKey> { key ->
        LinkConfirmScreen(linkId = key.linkId, onDone = onHome)
    }
}
