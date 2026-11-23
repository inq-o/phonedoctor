/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.inq_o.phonedoctor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.inq_o.phonedoctor.feature.onboarding.api.navigation.OnboardingNavKey
import io.github.inq_o.phonedoctor.feature.onboarding.impl.navigation.onboardingEntry
import io.github.inq_o.phonedoctor.feature.seniorappdetail.api.navigation.AppDetailNavKey
import io.github.inq_o.phonedoctor.feature.seniorappdetail.impl.navigation.appDetailEntry
import io.github.inq_o.phonedoctor.feature.seniorfamily.api.navigation.FamilyLinksNavKey
import io.github.inq_o.phonedoctor.feature.seniorfamily.api.navigation.LinkConfirmNavKey
import io.github.inq_o.phonedoctor.feature.seniorfamily.api.navigation.PairingCodeNavKey
import io.github.inq_o.phonedoctor.feature.seniorfamily.impl.navigation.seniorFamilyEntries
import io.github.inq_o.phonedoctor.feature.seniorhome.api.navigation.ScanHistoryNavKey
import io.github.inq_o.phonedoctor.feature.seniorhome.api.navigation.SeniorHomeNavKey
import io.github.inq_o.phonedoctor.feature.seniorhome.impl.navigation.seniorHomeEntries

/**
 * 첫 설정을 끝냈으면 홈, 아니면 첫 설정부터 시작한다. 알림을 눌러 들어온 경우 [pendingOpen]의 화면을 홈 위에 연다.
 */
@Composable
fun MainNavigation(
    onboardingDone: Boolean,
    pendingOpen: PendingOpen?,
    onOpened: () -> Unit,
) {
    // 시작 화면은 처음 한 번만 정한다. 첫 설정이 끝나면 아래 onFinished에서 홈으로 바꾼다
    val backStack = rememberNavBackStack(if (onboardingDone) SeniorHomeNavKey else OnboardingNavKey)

    // 첫 설정 화면이 스택에 있는 동안은 열지 않는다: 설정을 마치며 스택을 홈으로 바꿀 때 함께 지워지지 않게
    val inOnboarding = backStack.any { it is OnboardingNavKey }
    LaunchedEffect(pendingOpen, onboardingDone, inOnboarding) {
        if (pendingOpen != null && onboardingDone && !inOnboarding) {
            // 알림으로 연 화면은 홈 바로 위에: [홈으로]·뒤로가 예상대로 홈으로 가고, 같은 화면이 여러 장 쌓이지 않게
            backStack.clear()
            backStack.add(SeniorHomeNavKey)
            backStack.add(
                when (pendingOpen) {
                    is PendingOpen.Alert -> AppDetailNavKey(pendingOpen.alertId)
                    is PendingOpen.LinkRequest -> LinkConfirmNavKey(pendingOpen.linkId)
                    PendingOpen.FamilyLinks -> FamilyLinksNavKey
                },
            )
            onOpened()
        }
    }
    val toHome: () -> Unit = {
        backStack.clear()
        backStack.add(SeniorHomeNavKey)
    }

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator()
        ),
        entryProvider = entryProvider {
            onboardingEntry(
                onFinished = { connectFamily ->
                    backStack.clear()
                    backStack.add(SeniorHomeNavKey)
                    if (connectFamily) backStack.add(PairingCodeNavKey)
                },
            )
            seniorHomeEntries(
                onAlertClick = { backStack.add(AppDetailNavKey(it)) },
                onHistoryClick = { backStack.add(ScanHistoryNavKey) },
                onFamilyClick = { backStack.add(FamilyLinksNavKey) },
                onLinkRequestClick = { backStack.add(LinkConfirmNavKey(it)) },
                onBack = { backStack.removeLastOrNull() },
            )
            appDetailEntry(onBack = { backStack.removeLastOrNull() })
            seniorFamilyEntries(
                onAddFamily = { backStack.add(PairingCodeNavKey) },
                onAnswer = { backStack.add(LinkConfirmNavKey(it)) },
                // 코드 화면은 닫고 연결 확인으로
                onLinkRequested = { linkId ->
                    backStack.removeAll { it is PairingCodeNavKey || it is LinkConfirmNavKey }
                    backStack.add(LinkConfirmNavKey(linkId))
                },
                onHome = toHome,
                onBack = { backStack.removeLastOrNull() },
            )
        }
    )
}
