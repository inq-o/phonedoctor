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
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.inq_o.phonedoctor.core.model.DeviceRole
import io.github.inq_o.phonedoctor.feature.guardian.api.navigation.AlertDetailNavKey
import io.github.inq_o.phonedoctor.feature.guardian.api.navigation.AlertHistoryNavKey
import io.github.inq_o.phonedoctor.feature.guardian.api.navigation.EnterCodeNavKey
import io.github.inq_o.phonedoctor.feature.guardian.api.navigation.GuardianHomeNavKey
import io.github.inq_o.phonedoctor.feature.guardian.api.navigation.GuardianSettingsNavKey
import io.github.inq_o.phonedoctor.feature.guardian.api.navigation.GuardianSignInNavKey
import io.github.inq_o.phonedoctor.feature.guardian.api.navigation.LinkWaitingNavKey
import io.github.inq_o.phonedoctor.feature.guardian.impl.navigation.guardianEntries
import io.github.inq_o.phonedoctor.feature.onboarding.api.navigation.OnboardingNavKey
import io.github.inq_o.phonedoctor.feature.onboarding.api.navigation.RoleChoiceNavKey
import io.github.inq_o.phonedoctor.feature.onboarding.impl.navigation.onboardingEntry
import io.github.inq_o.phonedoctor.feature.seniorappdetail.api.navigation.AppDetailNavKey
import io.github.inq_o.phonedoctor.feature.seniorappdetail.impl.navigation.appDetailEntry
import io.github.inq_o.phonedoctor.feature.seniorfamily.api.navigation.FamilyLinksNavKey
import io.github.inq_o.phonedoctor.feature.seniorfamily.api.navigation.LinkConfirmNavKey
import io.github.inq_o.phonedoctor.feature.seniorfamily.api.navigation.PairingCodeNavKey
import io.github.inq_o.phonedoctor.feature.seniorfamily.impl.navigation.seniorFamilyEntries
import io.github.inq_o.phonedoctor.feature.seniorhome.api.navigation.ScanHistoryNavKey
import io.github.inq_o.phonedoctor.feature.seniorhome.api.navigation.SeniorHomeNavKey
import io.github.inq_o.phonedoctor.feature.seniorhome.api.navigation.SeniorSettingsNavKey
import io.github.inq_o.phonedoctor.feature.seniorhome.impl.navigation.seniorHomeEntries
import io.github.inq_o.phonedoctor.feature.seniorsites.api.navigation.SiteAlertNavKey
import io.github.inq_o.phonedoctor.feature.seniorsites.api.navigation.SitesNavKey
import io.github.inq_o.phonedoctor.feature.seniorsites.impl.navigation.seniorSitesEntries

/** 처음 쌓을 화면. 첫 설정·로그인 화면 밑에는 역할 선택을 깔아 뒤로 가기로 다시 고를 수 있게 한다 */
internal fun startStack(start: StartScreen, familyAvailable: Boolean): List<NavKey> = when (start) {
    StartScreen.ROLE_CHOICE -> listOf(RoleChoiceNavKey)
    StartScreen.SENIOR_ONBOARDING -> if (familyAvailable) listOf(RoleChoiceNavKey, OnboardingNavKey) else listOf(OnboardingNavKey)
    StartScreen.SENIOR_HOME -> listOf(SeniorHomeNavKey)
    StartScreen.GUARDIAN_SIGN_IN -> listOf(RoleChoiceNavKey, GuardianSignInNavKey)
    StartScreen.GUARDIAN_SIGN_IN_AGAIN -> listOf(GuardianSignInNavKey)
    StartScreen.GUARDIAN_HOME -> listOf(GuardianHomeNavKey)
}

/**
 * 알림을 눌러 연 화면을 놓을 스택. 홈이 맨 밑에 있고 로그인 중이 아닐 때만 연다: 첫 설정을 마치며 스택을 홈으로 바꿀 때
 * 함께 지워지지 않게, 다시 로그인하는 화면을 닫지 않게. null이면 아직 열 수 없음. 빈 목록이면 이 역할에 없는 화면이라 버린다
 */
internal fun pendingStack(stack: List<NavKey>, target: PendingOpen): List<NavKey>? = when (if (GuardianSignInNavKey in stack) null else stack.firstOrNull()) {
    // 알림으로 연 화면은 홈 바로 위에: [홈으로]·뒤로가 예상대로 홈으로 가고, 같은 화면이 여러 장 쌓이지 않게
    SeniorHomeNavKey -> when (target) {
        is PendingOpen.Alert -> listOf(SeniorHomeNavKey, AppDetailNavKey(target.alertId))
        is PendingOpen.LinkRequest -> listOf(SeniorHomeNavKey, LinkConfirmNavKey(target.linkId))
        PendingOpen.FamilyLinks -> listOf(SeniorHomeNavKey, FamilyLinksNavKey)
        // 보호자 요청 알림은 시니어 폰에 오지 않는다
        is PendingOpen.GuardianRequest -> emptyList()
        is PendingOpen.Site -> listOf(SeniorHomeNavKey, SiteAlertNavKey(target.site, openSettings = true))
    }
    // 보호자의 연결 알림(연결됨·끊김·연락 끊김)은 연결 목록이 있는 홈으로, 요청 알림은 홈 위에 G2. 시니어 알림은 보호자 폰에 오지 않는다
    GuardianHomeNavKey -> when (target) {
        PendingOpen.FamilyLinks -> listOf(GuardianHomeNavKey)
        is PendingOpen.GuardianRequest -> listOf(GuardianHomeNavKey, AlertDetailNavKey(target.alertId))
        else -> emptyList()
    }
    else -> null
}

/**
 * [start]의 화면부터 시작한다. 알림을 눌러 들어온 경우 [pendingOpen]의 화면을 홈 위에 연다.
 * @param guardianSignedIn O1에서 보호자를 골랐을 때 로그인 화면을 건너뛸지
 */
@Composable
fun MainNavigation(
    start: StartScreen,
    familyAvailable: Boolean,
    guardianSignedIn: () -> Boolean,
    pendingOpen: PendingOpen?,
    onOpened: () -> Unit,
) {
    // 시작 화면은 처음 한 번만 정한다. 이후에는 아래 콜백들이 스택을 바꾼다
    val backStack = rememberNavBackStack(*startStack(start, familyAvailable).toTypedArray())
    fun reset(vararg keys: NavKey) {
        backStack.clear()
        backStack.addAll(keys)
    }

    // 스택이 바뀔 때마다 다시 본다 (첫 설정·로그인을 마치면 연다)
    val current = backStack.toList()
    LaunchedEffect(pendingOpen, current) {
        val target = pendingOpen ?: return@LaunchedEffect
        val stack = pendingStack(current, target) ?: return@LaunchedEffect
        if (stack.isNotEmpty()) reset(*stack.toTypedArray())
        onOpened()
    }
    // 화면이 바뀌는 동안 같은 버튼을 두 번 눌러도 한 장만 (같은 키가 두 번 쌓이지 않게)
    fun push(key: NavKey) {
        if (backStack.lastOrNull() != key) backStack.add(key)
    }
    val toSeniorHome: () -> Unit = { reset(SeniorHomeNavKey) }
    val toGuardianHome: () -> Unit = { reset(GuardianHomeNavKey) }

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator()
        ),
        entryProvider = entryProvider {
            onboardingEntry(
                onRoleChosen = { role ->
                    // 역할 선택이 맨 위일 때만 (넘어가는 중에 다시 누른 것은 버린다)
                    if (backStack.lastOrNull() == RoleChoiceNavKey) {
                        when {
                            role == DeviceRole.SENIOR -> backStack.add(OnboardingNavKey)
                            guardianSignedIn() -> reset(GuardianHomeNavKey)
                            else -> backStack.add(GuardianSignInNavKey)
                        }
                    }
                },
                onFinished = { connectFamily ->
                    if (connectFamily) reset(SeniorHomeNavKey, PairingCodeNavKey) else reset(SeniorHomeNavKey)
                },
            )
            seniorHomeEntries(
                onAlertClick = { backStack.add(AppDetailNavKey(it)) },
                onHistoryClick = { backStack.add(ScanHistoryNavKey) },
                onSettingsClick = { push(SeniorSettingsNavKey) },
                onSitesClick = { push(SitesNavKey) },
                onFamilyClick = { push(FamilyLinksNavKey) },
                onLinkRequestClick = { backStack.add(LinkConfirmNavKey(it)) },
                onBack = { backStack.removeLastOrNull() },
            )
            appDetailEntry(onBack = { backStack.removeLastOrNull() })
            seniorSitesEntries(
                onOpenSite = { push(SiteAlertNavKey(it)) },
                onHome = toSeniorHome,
                onBack = { backStack.removeLastOrNull() },
            )
            seniorFamilyEntries(
                onAddFamily = { push(PairingCodeNavKey) },
                onAnswer = { backStack.add(LinkConfirmNavKey(it)) },
                // 코드 화면은 닫고 연결 확인으로
                onLinkRequested = { linkId ->
                    backStack.removeAll { it is PairingCodeNavKey || it is LinkConfirmNavKey }
                    backStack.add(LinkConfirmNavKey(linkId))
                },
                onHome = toSeniorHome,
                onBack = { backStack.removeLastOrNull() },
            )
            guardianEntries(
                // 뒤로 가기로 로그인 화면에 돌아오지 않게 홈 위에 코드 입력을 연다
                onSignedIn = { reset(GuardianHomeNavKey, EnterCodeNavKey) },
                // 밑에 역할 선택(처음 고를 때)이나 홈(로그인이 풀려 다시 할 때)이 있을 때만. 보호자로 등록한 폰은 역할을 바꾸지 않는다
                canLeaveSignIn = { backStack.size > 1 },
                onSignInBack = { backStack.removeLastOrNull() },
                onAddFamily = { push(EnterCodeNavKey) },
                // 코드 입력은 닫고 대기 화면으로 (뒤로 가면 홈)
                onRequested = { linkId, seniorName ->
                    backStack.removeAll { it is EnterCodeNavKey || it is LinkWaitingNavKey }
                    backStack.add(LinkWaitingNavKey(linkId, seniorName))
                },
                onSignInRequired = {
                    backStack.removeAll { it is EnterCodeNavKey }
                    push(GuardianSignInNavKey)
                },
                onHome = toGuardianHome,
                onRetry = {
                    backStack.removeAll { it is LinkWaitingNavKey }
                    backStack.add(EnterCodeNavKey)
                },
                onOpenRequest = { push(AlertDetailNavKey(it)) },
                onHistory = { push(AlertHistoryNavKey) },
                onBack = { backStack.removeLastOrNull() },
                onSettings = { push(GuardianSettingsNavKey) },
                // 모든 데이터를 지운 보호자 폰은 처음부터 (역할 선택)
                onDataDeleted = { reset(RoleChoiceNavKey) },
            )
        }
    )
}
