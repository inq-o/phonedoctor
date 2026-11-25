package io.github.inq_o.phonedoctor.feature.guardian.impl

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.SavedStateHandle
import androidx.credentials.exceptions.NoCredentialException
import io.github.inq_o.phonedoctor.core.data.DeviceNotRegisteredException
import io.github.inq_o.phonedoctor.core.model.FamilyDecision
import io.github.inq_o.phonedoctor.core.model.FamilyAnswer
import io.github.inq_o.phonedoctor.core.model.FamilyAlertStatus
import io.github.inq_o.phonedoctor.core.data.AlreadyAnsweredException
import io.github.inq_o.phonedoctor.core.data.AlertClosedException
import io.github.inq_o.phonedoctor.core.data.GuardianSignInRequiredException
import io.github.inq_o.phonedoctor.core.data.RoleMismatchException
import io.github.inq_o.phonedoctor.core.model.DeviceRole
import io.github.inq_o.phonedoctor.core.model.LinkStatus
import io.github.inq_o.phonedoctor.core.network.NotSignedInException
import kotlinx.coroutines.CompletableDeferred
import io.github.inq_o.phonedoctor.core.notifications.RequestChanges
import io.github.inq_o.phonedoctor.core.data.RecentLoginRequiredException
import io.github.inq_o.phonedoctor.core.data.UserSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.time.Duration
import java.time.Instant

class GuardianViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val family = FakeFamily()
    private val notifier = FakeNotifier()
    private val phoneNames = FakePhoneNames()
    private val requests = FakeAlerts()
    private val changes = RequestChanges()

    // O-G1

    @Test
    fun signInStartsOnceAndFinishesWithToken() = runTest(main.dispatcher) {
        val vm = GuardianSignInViewModel(family)
        assertTrue(vm.start())
        assertFalse(vm.start())
        vm.onToken("id-token")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.signedIn)
        assertEquals(listOf("id-token"), family.tokens)
        vm.consumeSignedIn()
        assertFalse(vm.uiState.value.signedIn)
        // 넘어가는 동안 버튼은 계속 막아 둔다
        assertTrue(vm.uiState.value.busy)
    }

    @Test
    fun signInFailuresAreExplainedExceptClosingThePicker() = runTest(main.dispatcher) {
        val vm = GuardianSignInViewModel(family)
        vm.start()
        vm.onFailed(GetCredentialCancellationException())
        assertEquals(SignInUiState(), vm.uiState.value)

        vm.start()
        vm.onFailed(NoCredentialException())
        assertEquals(SignInError.NO_ACCOUNT, vm.uiState.value.error)
        assertFalse(vm.uiState.value.busy)

        vm.start()
        family.fail["signIn"] = RoleMismatchException(DeviceRole.SENIOR)
        vm.onToken("t")
        advanceUntilIdle()
        assertEquals(SignInError.WRONG_ROLE, vm.uiState.value.error)

        vm.start()
        family.fail["signIn"] = IOException("offline")
        vm.onToken("t")
        advanceUntilIdle()
        assertEquals(SignInError.NETWORK, vm.uiState.value.error)
        assertEquals(SignInError.UNAVAILABLE, GoogleSignInUnavailableException().toSignInError())
    }

    @Test
    fun abandonedPickerLetsTheButtonWorkAgain() {
        val vm = GuardianSignInViewModel(family)
        vm.start()
        vm.onAbandoned()
        assertTrue(vm.start())
    }

    // O-G2

    @Test
    fun codeKeepsSixDigitsOnly() {
        val vm = EnterCodeViewModel(SavedStateHandle(), family)
        vm.onCodeChange("48 2-91a3 77")
        assertEquals("482913", vm.uiState.value.code)
        assertTrue(vm.uiState.value.canSubmit)
        vm.onCodeChange("4829")
        assertFalse(vm.uiState.value.canSubmit)
        vm.submit()
        assertTrue(family.calls.isEmpty())
    }

    @Test
    fun submitSendsOnceAndOpensWaiting() = runTest(main.dispatcher) {
        val vm = EnterCodeViewModel(SavedStateHandle(), family)
        val gate = CompletableDeferred<Unit>().also { family.gate["accept"] = it }
        vm.onCodeChange("482913")
        vm.submit()
        vm.submit()
        runCurrent()
        // 보내는 동안에는 코드를 바꿀 수 없다
        vm.onCodeChange("111111")
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("482913"), family.codes)
        assertEquals("new-482913", vm.uiState.value.requested?.linkId)
        assertEquals("", vm.uiState.value.code)
        vm.consumeRequested()
        assertNull(vm.uiState.value.requested)
    }

    @Test
    fun serverAnswersBecomeGuidance() = runTest(main.dispatcher) {
        val cases = listOf(
            apiError(404, "pairing-code-not-found") to CodeError.NOT_FOUND,
            apiError(410, "pairing-code-expired") to CodeError.EXPIRED,
            apiError(410, "pairing-code-used") to CodeError.USED,
            apiError(409, "already-linked") to CodeError.ALREADY_LINKED,
            apiError(409, "guardian-limit") to CodeError.LIMIT,
            apiError(429, "too-many-attempts", retryAfter = 42) to CodeError.TOO_MANY,
            apiError(503) to CodeError.BUSY,
            IOException("offline") to CodeError.NETWORK,
            RoleMismatchException(DeviceRole.SENIOR) to CodeError.WRONG_ROLE,
            // 등록(POST /devices)에서 서버가 다른 역할로 등록된 사용자라고 답함
            apiError(409, "role-mismatch") to CodeError.WRONG_ROLE,
        )
        val vm = EnterCodeViewModel(SavedStateHandle(), family)
        for ((error, expected) in cases) {
            family.fail["accept"] = error
            vm.onCodeChange("482913")
            vm.submit()
            advanceUntilIdle()
            assertEquals(error.toString(), expected, vm.uiState.value.error)
            assertFalse(vm.uiState.value.busy)
        }
        // 기다릴 시간은 429일 때 서버가 준 값
        family.fail["accept"] = apiError(429, "too-many-attempts", retryAfter = 42)
        vm.onCodeChange("482913")
        vm.submit()
        advanceUntilIdle()
        assertEquals(42L, vm.uiState.value.retryAfterSeconds)
        // 고치기 시작하면 안내를 지운다
        vm.onCodeChange("48291")
        assertNull(vm.uiState.value.error)
        assertNull(vm.uiState.value.retryAfterSeconds)
    }

    @Test
    fun signedOutGuardianGoesBackToSignIn() = runTest(main.dispatcher) {
        // 등록 전(Google 로그인 없음)과 등록 뒤(Firebase 사용자가 없어짐) 모두
        for (error in listOf(GuardianSignInRequiredException(), NotSignedInException())) {
            val vm = EnterCodeViewModel(SavedStateHandle(), family)
            family.fail["accept"] = error
            vm.onCodeChange("482913")
            vm.submit()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.signInRequired)
            assertNull(vm.uiState.value.error)
            vm.consumeSignInRequired()
            assertFalse(vm.uiState.value.signInRequired)
        }
        assertEquals(GuardianError.SIGNED_OUT, NotSignedInException().toGuardianError())
        assertEquals(GuardianError.WRONG_ROLE, apiError(409, "role-mismatch").toGuardianError())
    }

    @Test
    fun typedCodeSurvivesProcessRestore() = runTest(main.dispatcher) {
        val saved = SavedStateHandle()
        EnterCodeViewModel(saved, family).onCodeChange("4829")
        assertEquals("4829", EnterCodeViewModel(saved, family).uiState.value.code)

        val vm = EnterCodeViewModel(saved, family)
        vm.onCodeChange("482913")
        vm.submit()
        advanceUntilIdle()
        // 보낸 코드는 남기지 않는다
        assertEquals("", EnterCodeViewModel(saved, family).uiState.value.code)
    }

    @Test
    fun scannedPairingQrIsSentRightAway() = runTest(main.dispatcher) {
        val vm = EnterCodeViewModel(SavedStateHandle(), family)
        vm.onScanned("https://example.com/?c=482913")
        assertEquals(CodeError.NOT_PAIRING_QR, vm.uiState.value.error)
        vm.onScanned(null)
        assertEquals(CodeError.NOT_PAIRING_QR, vm.uiState.value.error)
        assertTrue(family.calls.isEmpty())

        vm.onScanned("phonedoctor://pair?c=482913")
        advanceUntilIdle()
        assertEquals(listOf("482913"), family.codes)
        assertNull(vm.uiState.value.error)

        vm.onScanFailed()
        assertEquals(CodeError.SCAN_FAILED, vm.uiState.value.error)
    }

    @Test
    fun pairingPayloadParsing() {
        assertEquals("482913", pairingCodeFrom("phonedoctor://pair?c=482913"))
        assertEquals("482913", pairingCodeFrom(" PhoneDoctor://pair?x=1&c=482913 "))
        assertEquals("482913", pairingCodeFrom("482913"))
        assertNull(pairingCodeFrom("phonedoctor://pair?c=48291"))
        assertNull(pairingCodeFrom("phonedoctor://other?c=482913"))
        assertNull(pairingCodeFrom("intent://pair?c=482913"))
        assertNull(pairingCodeFrom("phonedoctor://pair"))
        assertNull(pairingCodeFrom("not a uri %%"))
    }

    // O-G3

    @Test
    fun waitingWatchesUntilSeniorConfirms() = runTest(main.dispatcher) {
        family.links += link("l1", LinkStatus.PENDING, senior = "엄마")
        val vm = LinkWaitingViewModel("l1", "", family, notifier, phoneNames)
        assertEquals(LinkWaitingUiState.Waiting(""), vm.uiState.value)
        vm.startWatching()
        vm.startWatching()
        runCurrent()
        assertEquals(LinkWaitingUiState.Waiting("엄마"), vm.uiState.value)
        assertEquals(1, family.calls.count { it == "links" })

        family.fail["links"] = IOException("offline")
        advanceTimeBy(5_001)
        assertEquals(LinkWaitingUiState.Waiting("엄마"), vm.uiState.value)

        family.links[0] = link("l1", LinkStatus.ACTIVE, senior = "엄마")
        advanceTimeBy(5_001)
        assertEquals(LinkWaitingUiState.Connected("엄마"), vm.uiState.value)
        assertEquals(listOf("l1"), notifier.cancelled)
        // 연결되면 더 확인하지 않는다
        val polls = family.calls.size
        advanceTimeBy(60_000)
        assertEquals(polls, family.calls.size)
        vm.stopWatching()
    }

    @Test
    fun connectedSavesChosenNameOnlyOnThisPhone() = runTest(main.dispatcher) {
        val vm = LinkWaitingViewModel("l1", "김영희", family, notifier, phoneNames)
        var done = 0
        vm.saveName("엄마") { done++ }
        advanceUntilIdle()
        assertEquals(mapOf("l1" to "엄마"), phoneNames.names.value)
        // 건너뛰면 아무것도 저장하지 않는다 (서버로도 보내지 않음)
        vm.saveName(null) { done++ }
        advanceUntilIdle()
        assertEquals(mapOf("l1" to "엄마"), phoneNames.names.value)
        assertEquals(2, done)
        assertTrue(family.calls.isEmpty())
    }

    @Test
    fun declinedOrMissingLinkEndsWaiting() = runTest(main.dispatcher) {
        family.links += link("l1", LinkStatus.REVOKED)
        val declined = LinkWaitingViewModel("l1", "엄마", family, notifier, phoneNames)
        declined.startWatching()
        runCurrent()
        assertEquals(LinkWaitingUiState.Declined("엄마"), declined.uiState.value)

        val missing = LinkWaitingViewModel("gone", "아빠", family, notifier, phoneNames)
        missing.startWatching()
        runCurrent()
        assertEquals(LinkWaitingUiState.Declined("아빠"), missing.uiState.value)
        assertEquals(listOf("l1", "gone"), notifier.cancelled)
    }

    @Test
    fun waitingStopsWhenScreenLeaves() = runTest(main.dispatcher) {
        family.links += link("l1", LinkStatus.PENDING)
        val vm = LinkWaitingViewModel("l1", "엄마", family, notifier, phoneNames)
        vm.startWatching()
        runCurrent()
        vm.stopWatching()
        advanceTimeBy(60_000)
        assertEquals(1, family.calls.size)
    }

    // G1

    private val now = Instant.parse("2026-11-23T03:00:00Z")

    private fun home() = GuardianHomeViewModel(family, requests, changes).also { it.now = { now } }

    @Test
    fun homeListsActiveFirstAndFlagsLostContact() = runTest(main.dispatcher) {
        family.links += listOf(
            link("pending", LinkStatus.PENDING, createdAt = 1),
            link("revoked", LinkStatus.REVOKED, createdAt = 2),
            link("lost", LinkStatus.ACTIVE, createdAt = 3, activatedAt = now.minus(Duration.ofDays(5)), lastCheckedAt = now.minus(Duration.ofHours(48))),
            link("fine", LinkStatus.ACTIVE, createdAt = 4, activatedAt = now.minus(Duration.ofDays(5)), lastCheckedAt = now.minus(Duration.ofHours(47))),
        )
        val vm = home()
        vm.refresh()
        advanceUntilIdle()
        val ready = vm.uiState.value as GuardianHomeUiState.Ready
        assertEquals(listOf("lost", "fine", "pending"), ready.links.map { it.link.linkId })
        assertEquals(listOf(true, false, false), ready.links.map { it.contactLost })
    }

    @Test
    fun contactLostCountsFromTheLaterOfReportAndLinkLikeTheServer() {
        val old = now.minus(Duration.ofHours(49))
        // 연결한 뒤 보고가 한 번도 없으면 연결 시각부터 센다 (CONTACT_LOST 알림과 같은 판단)
        assertTrue(link("a", LinkStatus.ACTIVE, activatedAt = old).isContactLost(now))
        assertTrue(link("a", LinkStatus.ACTIVE, activatedAt = old, lastCheckedAt = old).isContactLost(now))
        // 보고가 오래돼도 방금 연결했으면 연결 시각부터 센다
        assertFalse(link("a", LinkStatus.ACTIVE, activatedAt = now.minus(Duration.ofHours(1)), lastCheckedAt = old).isContactLost(now))
        assertFalse(link("p", LinkStatus.PENDING, activatedAt = old, lastCheckedAt = old).isContactLost(now))
    }

    @Test
    fun homeKeepsShownListWhenRefreshFails() = runTest(main.dispatcher) {
        val vm = home()
        family.fail["links"] = IOException("offline")
        vm.refresh()
        advanceUntilIdle()
        assertEquals(GuardianHomeUiState.Failed(GuardianError.NETWORK), vm.uiState.value)

        family.links += link("l1", LinkStatus.ACTIVE)
        vm.refresh()
        advanceUntilIdle()
        family.fail["links"] = apiError(500)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf("l1"), (vm.uiState.value as GuardianHomeUiState.Ready).links.map { it.link.linkId })
    }

    @Test
    fun revokeRemovesLinkAndTreatsGoneAsRevoked() = runTest(main.dispatcher) {
        family.links += listOf(link("a", LinkStatus.ACTIVE, senior = "엄마"), link("p", LinkStatus.PENDING, senior = "아빠"), link("g", LinkStatus.ACTIVE))
        val vm = home()
        vm.refresh()
        advanceUntilIdle()

        vm.revoke(family.links[0])
        vm.revoke(family.links[1]) // 끊는 중에는 다른 연결을 끊지 않는다
        advanceUntilIdle()
        var ready = vm.uiState.value as GuardianHomeUiState.Ready
        assertEquals(GuardianMessage.Revoked("a", "엄마", wasPending = false), ready.message)
        assertEquals(listOf("g", "p"), ready.links.map { it.link.linkId })
        assertEquals(1, family.calls.count { it == "revoke" })

        family.fail["revoke"] = apiError(404)
        vm.revoke(ready.links[1].link)
        advanceUntilIdle()
        ready = vm.uiState.value as GuardianHomeUiState.Ready
        assertEquals(GuardianMessage.Revoked("p", "아빠", wasPending = true), ready.message)

        family.fail["revoke"] = apiError(503)
        vm.revoke(ready.links[0].link)
        advanceUntilIdle()
        ready = vm.uiState.value as GuardianHomeUiState.Ready
        assertEquals(GuardianMessage.Failed(GuardianError.BUSY), ready.message)
        assertEquals(listOf("g"), ready.links.map { it.link.linkId })

        family.fail["revoke"] = DeviceNotRegisteredException()
        vm.revoke(ready.links[0].link)
        advanceUntilIdle()
        assertTrue((vm.uiState.value as GuardianHomeUiState.Ready).links.isEmpty())
    }

    // 요청 (G1·G2·G3)

    @Test
    fun homeShowsUnansweredRequestsAndHistoryButton() = runTest(main.dispatcher) {
        family.links += link("l1", LinkStatus.ACTIVE)
        requests.alerts += listOf(
            request("open"),
            request("notice", notice = true),
            request("answered", status = FamilyAlertStatus.DECIDED, answer = FamilyAnswer(FamilyDecision.ALLOW, "민지", null, Instant.EPOCH)),
        )
        val vm = home()
        vm.refresh()
        advanceUntilIdle()
        val ready = vm.uiState.value as GuardianHomeUiState.Ready
        assertEquals(listOf("open", "notice"), ready.requests.map { it.alertId })
        // 열린 요청만 받아 온다 (목록이 길어도 답할 것이 잘리지 않게)
        assertEquals(listOf(FamilyAlertStatus.OPEN), requests.statusAsked)
        assertTrue(ready.hasHistory)
    }

    @Test
    fun requestFailureStillShowsLinksAndKeepsShownRequests() = runTest(main.dispatcher) {
        family.links += link("l1", LinkStatus.ACTIVE)
        requests.alerts += request("open")
        val vm = home()
        vm.refresh()
        advanceUntilIdle()
        requests.fail["alerts"] = IOException("offline")
        vm.refresh()
        advanceUntilIdle()
        val ready = vm.uiState.value as GuardianHomeUiState.Ready
        assertEquals(listOf("l1"), ready.links.map { it.link.linkId })
        assertEquals(listOf("open"), ready.requests.map { it.alertId })
    }

    private fun detail(id: String = "a1") = AlertDetailViewModel(id, requests, notifier, changes).also { it.onShown() }

    @Test
    fun detailClearsNotificationAndSendsAnswerWithMessage() = runTest(main.dispatcher) {
        requests.alerts += request("a1")
        val vm = detail()
        advanceUntilIdle()
        assertEquals(listOf("request:a1"), notifier.cancelled)
        vm.onMessageChange("엄마 이거 광고 앱이에요" + "!".repeat(60))
        assertEquals(40, (vm.uiState.value as AlertDetailUiState.Ready).message.length)
        vm.onMessageChange("엄마 이거 광고 앱이에요")
        vm.decide(FamilyDecision.RECOMMEND_UNINSTALL)
        vm.decide(FamilyDecision.ALLOW)
        advanceUntilIdle()
        assertEquals(listOf(Triple("a1", FamilyDecision.RECOMMEND_UNINSTALL, "엄마 이거 광고 앱이에요")), requests.decided)
        val ready = vm.uiState.value as AlertDetailUiState.Ready
        assertTrue(ready.answered)
        assertFalse(ready.alert.answerable)
    }

    @Test
    fun allowSendsTheMessageTooAndNoticeCannotBeAllowed() = runTest(main.dispatcher) {
        requests.alerts += listOf(request("a1"), request("n1", notice = true))
        val vm = detail()
        advanceUntilIdle()
        vm.onMessageChange("엄마 이건 괜찮아요")
        vm.decide(FamilyDecision.ALLOW)
        advanceUntilIdle()
        assertEquals(Triple("a1", FamilyDecision.ALLOW, "엄마 이건 괜찮아요"), requests.decided.single())

        val notice = detail("n1")
        advanceUntilIdle()
        notice.decide(FamilyDecision.ALLOW)
        advanceUntilIdle()
        assertEquals(1, requests.decided.size)
    }

    @Test
    fun someoneElseAnsweredFirst() = runTest(main.dispatcher) {
        requests.alerts += request("a1")
        val vm = detail()
        advanceUntilIdle()
        val first = FamilyAnswer(FamilyDecision.RECOMMEND_UNINSTALL, "철수", null, Instant.EPOCH)
        requests.fail["decide"] = AlreadyAnsweredException(first)
        vm.decide(FamilyDecision.ALLOW)
        advanceUntilIdle()
        val ready = vm.uiState.value as AlertDetailUiState.Ready
        assertEquals(first, ready.answeredFirst)
        assertNull(ready.sending)
    }

    @Test
    fun detailErrorsAreExplained() = runTest(main.dispatcher) {
        val missing = detail("gone")
        advanceUntilIdle()
        assertEquals(AlertDetailUiState.NotFound, missing.uiState.value)

        requests.alerts += request("a1")
        requests.fail["alert"] = IOException("offline")
        val vm = detail()
        advanceUntilIdle()
        assertEquals(AlertDetailUiState.Failed(GuardianError.NETWORK), vm.uiState.value)
        vm.load()
        advanceUntilIdle()
        requests.fail["decide"] = IOException("offline")
        vm.decide(FamilyDecision.RECOMMEND_UNINSTALL)
        advanceUntilIdle()
        assertEquals(GuardianError.NETWORK, (vm.uiState.value as AlertDetailUiState.Ready).error)

        requests.fail["decide"] = AlertClosedException()
        vm.decide(FamilyDecision.RECOMMEND_UNINSTALL)
        advanceUntilIdle()
        assertNull((vm.uiState.value as AlertDetailUiState.Ready).sending)
    }

    @Test
    fun historyListsEverythingAndKeepsListOnFailure() = runTest(main.dispatcher) {
        requests.alerts += listOf(request("a1"), request("a2", status = FamilyAlertStatus.EXPIRED))
        val vm = AlertHistoryViewModel(requests)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(2, (vm.uiState.value as AlertHistoryUiState.Ready).alerts.size)
        requests.fail["alerts"] = IOException("offline")
        vm.refresh()
        advanceUntilIdle()
        assertEquals(2, (vm.uiState.value as AlertHistoryUiState.Ready).alerts.size)
    }

    @Test
    fun rejectedAnswerHidesButtonsEvenIfReloadFails() = runTest(main.dispatcher) {
        requests.alerts += request("a1")
        val vm = detail()
        advanceUntilIdle()
        requests.fail["decide"] = AlertClosedException()
        requests.fail["alert"] = IOException("offline")
        vm.decide(FamilyDecision.RECOMMEND_UNINSTALL)
        advanceUntilIdle()
        val ready = vm.uiState.value as AlertDetailUiState.Ready
        assertTrue(ready.rejected)
        assertFalse(ready.canAnswer)
        vm.decide(FamilyDecision.RECOMMEND_UNINSTALL)
        advanceUntilIdle()
        assertTrue(requests.decided.isEmpty())
    }

    @Test
    fun networkErrorRereadsTheRequestToShowAnAnswerThatGotThrough() = runTest(main.dispatcher) {
        requests.alerts += request("a1")
        val vm = detail()
        advanceUntilIdle()
        // 서버는 받았는데 응답을 잃음
        requests.alerts[0] = request("a1", status = FamilyAlertStatus.DECIDED, answer = FamilyAnswer(FamilyDecision.RECOMMEND_UNINSTALL, "민지", null, Instant.EPOCH))
        requests.fail["decide"] = IOException("offline")
        vm.decide(FamilyDecision.RECOMMEND_UNINSTALL)
        advanceUntilIdle()
        val ready = vm.uiState.value as AlertDetailUiState.Ready
        assertFalse(ready.canAnswer)
        assertEquals(FamilyDecision.RECOMMEND_UNINSTALL, ready.alert.answer?.decision)
    }

    @Test
    fun reopeningTheSameRequestReloadsIt() = runTest(main.dispatcher) {
        requests.alerts += request("a1")
        val vm = detail()
        advanceUntilIdle()
        requests.alerts[0] = request("a1", status = FamilyAlertStatus.RESOLVED)
        vm.onShown()
        advanceUntilIdle()
        assertEquals(FamilyAlertStatus.RESOLVED, (vm.uiState.value as AlertDetailUiState.Ready).alert.status)
        assertEquals(listOf("request:a1", "request:a1"), notifier.cancelled)
    }

    @Test
    fun aSlowReloadDoesNotBringBackTheButtonsAfterAnswering() = runTest(main.dispatcher) {
        requests.alerts += request("a1")
        val vm = detail()
        advanceUntilIdle()
        // 다시 읽기가 늦게 끝나는 동안 답을 보냄
        val gate = CompletableDeferred<Unit>().also { requests.gate["alert"] = it }
        vm.onShown()
        runCurrent()
        vm.decide(FamilyDecision.RECOMMEND_UNINSTALL)
        advanceUntilIdle()
        requests.alerts[0] = request("a1") // 늦게 온 예전 상태
        gate.complete(Unit)
        advanceUntilIdle()
        val ready = vm.uiState.value as AlertDetailUiState.Ready
        assertTrue(ready.answered)
        assertFalse(ready.canAnswer)
        // 늦게 온 예전 상태(열림)로 덮지 않는다
        assertEquals(FamilyAlertStatus.DECIDED, ready.alert.status)
    }

    @Test
    fun pushSignalsReloadTheOpenScreens() = runTest(main.dispatcher) {
        family.links += link("l1", LinkStatus.ACTIVE)
        requests.alerts += request("a1")
        val home = home()
        home.refresh()
        val vm = detail()
        advanceUntilIdle()
        requests.alerts[0] = request("a1", status = FamilyAlertStatus.DECIDED, answer = FamilyAnswer(FamilyDecision.ALLOW, "철수", null, Instant.EPOCH))
        changes.changed("other")
        advanceUntilIdle()
        assertTrue((vm.uiState.value as AlertDetailUiState.Ready).alert.answerable)
        changes.changed("a1")
        advanceUntilIdle()
        assertFalse((vm.uiState.value as AlertDetailUiState.Ready).alert.answerable)
        assertTrue((home.uiState.value as GuardianHomeUiState.Ready).requests.isEmpty())
    }

    @Test
    fun revokingTheLastLinkClearsRequests() = runTest(main.dispatcher) {
        family.links += link("l1", LinkStatus.ACTIVE)
        requests.alerts += request("a1")
        val vm = home()
        vm.refresh()
        advanceUntilIdle()
        assertEquals(1, (vm.uiState.value as GuardianHomeUiState.Ready).requests.size)
        vm.revoke(family.links[0])
        advanceUntilIdle()
        val ready = vm.uiState.value as GuardianHomeUiState.Ready
        assertTrue(ready.requests.isEmpty())
        assertFalse(ready.hasHistory)
    }

    // G4

    private class FakeSettings : UserSettingsRepository {
        var cleared = 0
        override val onboardingDone: Flow<Boolean> = flowOf(false)
        override val chosenRole: Flow<DeviceRole?> = flowOf(DeviceRole.GUARDIAN)
        override suspend fun completeOnboarding(disclosureVersion: Int) = Unit
        override suspend fun chooseRole(role: DeviceRole) = Unit
        override suspend fun clearChosenRole() {
            cleared++
        }
    }

    @Test
    fun settingsSaveCautionAndName() = runTest(main.dispatcher) {
        val vm = GuardianSettingsViewModel(family, FakeSettings(), requests)
        vm.setNotifyCaution(true)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.notifyCaution)
        assertTrue(vm.uiState.value.saved)
        vm.rename("  민지  ")
        advanceUntilIdle()
        assertEquals("민지", vm.uiState.value.displayName)
        vm.rename("가".repeat(21))
        advanceUntilIdle()
        assertEquals("민지", vm.uiState.value.displayName)
        family.fail["caution"] = IOException("offline")
        vm.setNotifyCaution(false)
        advanceUntilIdle()
        assertEquals(GuardianError.NETWORK, vm.uiState.value.error)
        assertTrue(vm.uiState.value.notifyCaution)
    }

    @Test
    fun deletingAllDataResetsRoleAndAsksForReauthWhenNeeded() = runTest(main.dispatcher) {
        val settings = FakeSettings()
        val vm = GuardianSettingsViewModel(family, settings, requests)
        family.fail["delete"] = RecentLoginRequiredException()
        vm.deleteAll()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.needsReauth)
        assertEquals(0, settings.cleared)

        assertTrue(vm.startReauth())
        assertFalse(vm.startReauth())
        vm.onReauthToken("token")
        advanceUntilIdle()
        assertEquals(listOf("token"), family.reauthTokens)
        assertEquals(1, family.deletes)
        assertEquals(1, settings.cleared)
        assertTrue(vm.uiState.value.deleted)
        assertFalse(vm.uiState.value.needsReauth)
        vm.consumeDeleted()
        assertFalse(vm.uiState.value.deleted)
    }

    @Test
    fun reauthWithAnotherAccountAsksForTheFirstOne() = runTest(main.dispatcher) {
        val vm = GuardianSettingsViewModel(family, FakeSettings(), requests)
        vm.startReauth()
        family.fail["reauth"] = io.github.inq_o.phonedoctor.core.data.ReauthAccountMismatchException()
        vm.onReauthToken("other")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.wrongAccount)
        assertEquals(0, family.deletes)
        assertTrue(vm.startReauth())
        assertFalse(vm.uiState.value.wrongAccount)
    }

    @Test
    fun feedbackSwitchIsLocalOnly() = runTest(main.dispatcher) {
        val vm = GuardianSettingsViewModel(family, FakeSettings(), requests)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.falsePositiveFeedback)
        vm.setFalsePositiveFeedback(false)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.falsePositiveFeedback)
        assertTrue(family.calls.isEmpty())
    }

    @Test
    fun closingTheReauthPickerIsQuiet() {
        val vm = GuardianSettingsViewModel(family, FakeSettings(), requests)
        vm.startReauth()
        vm.onReauthFailed(androidx.credentials.exceptions.GetCredentialCancellationException())
        assertFalse(vm.uiState.value.working)
        assertNull(vm.uiState.value.error)
    }
}
