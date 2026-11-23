package io.github.inq_o.phonedoctor.feature.guardian.impl

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import io.github.inq_o.phonedoctor.core.data.DeviceNotRegisteredException
import io.github.inq_o.phonedoctor.core.data.GuardianSignInRequiredException
import io.github.inq_o.phonedoctor.core.data.RoleMismatchException
import io.github.inq_o.phonedoctor.core.model.DeviceRole
import io.github.inq_o.phonedoctor.core.model.LinkStatus
import kotlinx.coroutines.CompletableDeferred
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
        val vm = EnterCodeViewModel(family)
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
        val vm = EnterCodeViewModel(family)
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
            apiError(410, "pairing-code-used") to CodeError.EXPIRED,
            apiError(409, "already-linked") to CodeError.ALREADY_LINKED,
            apiError(409, "guardian-limit") to CodeError.LIMIT,
            apiError(429, "too-many-attempts", retryAfter = 42) to CodeError.TOO_MANY,
            apiError(503) to CodeError.BUSY,
            IOException("offline") to CodeError.NETWORK,
            RoleMismatchException(DeviceRole.SENIOR) to CodeError.WRONG_ROLE,
        )
        val vm = EnterCodeViewModel(family)
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
        val vm = EnterCodeViewModel(family)
        family.fail["accept"] = GuardianSignInRequiredException()
        vm.onCodeChange("482913")
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.signInRequired)
        assertNull(vm.uiState.value.error)
        vm.consumeSignInRequired()
        assertFalse(vm.uiState.value.signInRequired)
    }

    @Test
    fun scannedPairingQrIsSentRightAway() = runTest(main.dispatcher) {
        val vm = EnterCodeViewModel(family)
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
        val vm = LinkWaitingViewModel("l1", "부모님", family, notifier)
        assertEquals(LinkWaitingUiState.Waiting("부모님"), vm.uiState.value)
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
    fun declinedOrMissingLinkEndsWaiting() = runTest(main.dispatcher) {
        family.links += link("l1", LinkStatus.REVOKED)
        val declined = LinkWaitingViewModel("l1", "엄마", family, notifier)
        declined.startWatching()
        runCurrent()
        assertEquals(LinkWaitingUiState.Declined("엄마"), declined.uiState.value)

        val missing = LinkWaitingViewModel("gone", "아빠", family, notifier)
        missing.startWatching()
        runCurrent()
        assertEquals(LinkWaitingUiState.Declined("아빠"), missing.uiState.value)
        assertTrue(notifier.cancelled.isEmpty())
    }

    @Test
    fun waitingStopsWhenScreenLeaves() = runTest(main.dispatcher) {
        family.links += link("l1", LinkStatus.PENDING)
        val vm = LinkWaitingViewModel("l1", "엄마", family, notifier)
        vm.startWatching()
        runCurrent()
        vm.stopWatching()
        advanceTimeBy(60_000)
        assertEquals(1, family.calls.size)
    }

    // G1

    private val now = Instant.parse("2026-11-23T03:00:00Z")

    private fun home() = GuardianHomeViewModel(family).also { it.now = { now } }

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
    fun contactLostUsesActivationWhenNoReportYet() {
        val activated = now.minus(Duration.ofHours(49))
        assertTrue(link("a", LinkStatus.ACTIVE, activatedAt = activated).isContactLost(now))
        assertFalse(link("a", LinkStatus.ACTIVE, activatedAt = now.minus(Duration.ofHours(1))).isContactLost(now))
        assertFalse(link("p", LinkStatus.PENDING, activatedAt = activated).isContactLost(now))
        assertFalse(link("x", LinkStatus.ACTIVE).isContactLost(now))
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
        assertEquals(GuardianMessage.Revoked("엄마", wasPending = false), ready.message)
        assertEquals(listOf("g", "p"), ready.links.map { it.link.linkId })
        assertEquals(1, family.calls.count { it == "revoke" })

        family.fail["revoke"] = apiError(404)
        vm.revoke(ready.links[1].link)
        advanceUntilIdle()
        ready = vm.uiState.value as GuardianHomeUiState.Ready
        assertEquals(GuardianMessage.Revoked("아빠", wasPending = true), ready.message)

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
}
