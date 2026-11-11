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
import io.github.inq_o.phonedoctor.feature.seniorhome.api.navigation.ScanHistoryNavKey
import io.github.inq_o.phonedoctor.feature.seniorhome.api.navigation.SeniorHomeNavKey
import io.github.inq_o.phonedoctor.feature.seniorhome.impl.navigation.seniorHomeEntries

/**
 * 첫 설정을 끝냈으면 홈, 아니면 첫 설정부터 시작한다. 알림을 눌러 들어온 경우 [pendingAlertId]의 앱 상세를 홈 위에 연다.
 */
@Composable
fun MainNavigation(
    onboardingDone: Boolean,
    pendingAlertId: Long?,
    onAlertOpened: () -> Unit,
) {
    // 시작 화면은 처음 한 번만 정한다. 첫 설정이 끝나면 아래 onFinished에서 홈으로 바꾼다
    val backStack = rememberNavBackStack(if (onboardingDone) SeniorHomeNavKey else OnboardingNavKey)

    LaunchedEffect(pendingAlertId, onboardingDone) {
        if (pendingAlertId != null && onboardingDone) {
            backStack.removeAll { it is AppDetailNavKey }
            backStack.add(AppDetailNavKey(pendingAlertId))
            onAlertOpened()
        }
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
                onFinished = {
                    backStack.clear()
                    backStack.add(SeniorHomeNavKey)
                },
            )
            seniorHomeEntries(
                onAlertClick = { backStack.add(AppDetailNavKey(it)) },
                onHistoryClick = { backStack.add(ScanHistoryNavKey) },
                onBack = { backStack.removeLastOrNull() },
            )
            appDetailEntry(onBack = { backStack.removeLastOrNull() })
        }
    )
}
