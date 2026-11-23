package io.github.inq_o.phonedoctor.feature.seniorfamily.impl

import io.github.inq_o.phonedoctor.core.data.DeviceNotRegisteredException
import io.github.inq_o.phonedoctor.core.data.RoleMismatchException
import io.github.inq_o.phonedoctor.core.model.DeviceRole
import io.github.inq_o.phonedoctor.core.model.LinkStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.time.Instant

class SeniorFamilyViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val family = FakeFamily()
    private val notifier = FakeNotifier()

    // O-S4 연결 코드

    @Test
    fun codeScreenGetsCodeOnOpen() = runTest(main.dispatcher) {
        val vm = PairingCodeViewModel(family)
        assertEquals(PairingCodeUiState.Loading, vm.uiState.value)
        advanceUntilIdle()
        assertEquals("000001", (vm.uiState.value as PairingCodeUiState.Ready).code.code)
    }

    @Test
    fun codeFailureShowsReasonAndRetryGetsNewCode() = runTest(main.dispatcher) {
        family.fail["code"] = IOException("offline")
        val vm = PairingCodeViewModel(family)
        advanceUntilIdle()
        assertEquals(PairingCodeUiState.Failed(FamilyError.NETWORK), vm.uiState.value)
        vm.newCode()
        advanceUntilIdle()
        assertTrue(vm.uiState.value is PairingCodeUiState.Ready)
    }

    @Test
    fun codeErrorsMapToMessages() = runTest(main.dispatcher) {
        for ((error, expected) in listOf(
            apiError(429) to FamilyError.BUSY,
            apiError(503) to FamilyError.BUSY,
            apiError(403, "forbidden") to FamilyError.UNKNOWN,
            RoleMismatchException(DeviceRole.GUARDIAN) to FamilyError.WRONG_ROLE,
        )) {
            family.fail["code"] = error
            val vm = PairingCodeViewModel(family)
            advanceUntilIdle()
            assertEquals(PairingCodeUiState.Failed(expected), vm.uiState.value)
        }
    }

    @Test
    fun pressingNewCodeWhileLoadingSendsOnce() = runTest(main.dispatcher) {
        family.gate["code"] = CompletableDeferred()
        val vm = PairingCodeViewModel(family)
        runCurrent()
        vm.newCode()
        vm.newCode()
        family.gate.remove("code")!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("code"), family.calls)
    }

    @Test
    fun watchingOpensConfirmWhenGuardianEntersCode() = runTest(main.dispatcher) {
        val vm = PairingCodeViewModel(family)
        vm.now = { Instant.ofEpochSecond(0) }
        advanceUntilIdle()
        vm.startWatching()
        advanceTimeBy(5_001)
        assertNull(vm.linkRequested.value)
        // 이미 연결된 가족은 무시하고, 확인 대기 중 가장 최근 요청을 연다
        family.links += link("old", LinkStatus.ACTIVE, createdAt = 1)
        family.links += link("p1", LinkStatus.PENDING, createdAt = 2)
        family.links += link("p2", LinkStatus.PENDING, createdAt = 3)
        advanceTimeBy(5_000)
        assertEquals("p2", vm.linkRequested.value)
        vm.onLinkOpened()
        assertNull(vm.linkRequested.value)
        vm.stopWatching()
    }

    @Test
    fun oldUnansweredRequestDoesNotHijackNewCode() = runTest(main.dispatcher) {
        // 코드 발급 시각 = 만료 - 10분 = 9400초. 그 전에 생겨 답하지 않고 둔 요청은 이 화면에서 열지 않는다
        family.codeExpiresAt = Instant.ofEpochSecond(10_000)
        family.links += link("old", LinkStatus.PENDING, name = "철수", createdAt = 100)
        val vm = PairingCodeViewModel(family)
        vm.now = { Instant.ofEpochSecond(9_500) }
        advanceUntilIdle()
        vm.check()
        assertNull(vm.linkRequested.value)
        family.links += link("new", LinkStatus.PENDING, createdAt = 9_400)
        vm.check()
        assertEquals("new", vm.linkRequested.value)
    }

    @Test
    fun watchingChecksRightAwayWhenScreenShows() = runTest(main.dispatcher) {
        val vm = PairingCodeViewModel(family)
        vm.now = { Instant.ofEpochSecond(700) }
        advanceUntilIdle()
        vm.startWatching()
        runCurrent()
        assertEquals(true, (vm.uiState.value as PairingCodeUiState.Ready).expired)
        vm.stopWatching()
    }

    @Test
    fun watchingStopsWhenScreenHidden() = runTest(main.dispatcher) {
        val vm = PairingCodeViewModel(family)
        advanceUntilIdle()
        vm.startWatching()
        vm.startWatching()
        advanceTimeBy(5_001)
        vm.stopWatching()
        family.calls.clear()
        advanceTimeBy(60_000)
        assertTrue(family.calls.isEmpty())
    }

    @Test
    fun watchFailureIsIgnored() = runTest(main.dispatcher) {
        val vm = PairingCodeViewModel(family)
        vm.now = { Instant.ofEpochSecond(0) }
        advanceUntilIdle()
        family.fail["links"] = IOException("offline")
        vm.check()
        assertTrue(vm.uiState.value is PairingCodeUiState.Ready)
        family.links += link("p1", LinkStatus.PENDING)
        vm.check()
        assertEquals("p1", vm.linkRequested.value)
    }

    @Test
    fun expiredCodeAsksForNewOne() = runTest(main.dispatcher) {
        val vm = PairingCodeViewModel(family)
        advanceUntilIdle()
        vm.now = { Instant.ofEpochSecond(599) }
        vm.check()
        assertEquals(false, (vm.uiState.value as PairingCodeUiState.Ready).expired)
        vm.now = { Instant.ofEpochSecond(600) }
        vm.check()
        assertEquals(true, (vm.uiState.value as PairingCodeUiState.Ready).expired)
        vm.newCode()
        advanceUntilIdle()
        val ready = vm.uiState.value as PairingCodeUiState.Ready
        assertEquals("000002", ready.code.code)
        assertEquals(false, ready.expired)
    }

    // S3 연결 확인

    private fun confirmVm(id: String = "p1") = LinkConfirmViewModel(id, family, notifier)

    @Test
    fun confirmShowsPendingRequestAndConnects() = runTest(main.dispatcher) {
        family.links += link("p1", LinkStatus.PENDING, name = "민지")
        val vm = confirmVm()
        advanceUntilIdle()
        assertEquals("민지", (vm.uiState.value as LinkConfirmUiState.Ready).link.guardianName)
        vm.confirm()
        vm.confirm()
        advanceUntilIdle()
        assertEquals(LinkConfirmUiState.Connected("민지"), vm.uiState.value)
        assertEquals(1, family.calls.count { it == "confirm:p1" })
        assertEquals(listOf("p1"), notifier.cancelledLinks)
    }

    @Test
    fun declineRevokes() = runTest(main.dispatcher) {
        family.links += link("p1", LinkStatus.PENDING)
        val vm = confirmVm()
        advanceUntilIdle()
        vm.decline()
        advanceUntilIdle()
        assertEquals(LinkConfirmUiState.Declined("민지"), vm.uiState.value)
        assertTrue("revoke:p1" in family.calls)
        assertEquals(LinkStatus.REVOKED, family.links.single().status)
    }

    @Test
    fun declineOfVanishedRequestStillDeclines() = runTest(main.dispatcher) {
        family.links += link("p1", LinkStatus.PENDING)
        val vm = confirmVm()
        advanceUntilIdle()
        family.fail["revoke:p1"] = apiError(404)
        vm.decline()
        advanceUntilIdle()
        assertEquals(LinkConfirmUiState.Declined("민지"), vm.uiState.value)
    }

    @Test
    fun answeredOrMissingRequestIsNotAskedAgain() = runTest(main.dispatcher) {
        family.links += link("a1", LinkStatus.ACTIVE)
        family.links += link("r1", LinkStatus.REVOKED)
        val active = confirmVm("a1")
        val revoked = confirmVm("r1")
        val missing = confirmVm("x")
        advanceUntilIdle()
        assertEquals(LinkConfirmUiState.Connected("민지"), active.uiState.value)
        assertEquals(LinkConfirmUiState.Gone, revoked.uiState.value)
        assertEquals(LinkConfirmUiState.Gone, missing.uiState.value)
        // 남아 있던 알림도 지운다
        assertEquals(setOf("a1", "r1", "x"), notifier.cancelledLinks.toSet())
    }

    @Test
    fun guardianLimitKeepsRequestSoSeniorCanStillDecline() = runTest(main.dispatcher) {
        family.links += link("p1", LinkStatus.PENDING)
        val vm = confirmVm()
        advanceUntilIdle()
        family.fail["confirm:p1"] = apiError(409, "guardian-limit")
        vm.confirm()
        advanceUntilIdle()
        val limited = vm.uiState.value as LinkConfirmUiState.Ready
        assertTrue(limited.limitReached)
        assertEquals(false, limited.busy)
        // 요청이 남아 있으니 알림도 남긴다
        assertTrue(notifier.cancelledLinks.isEmpty())
        vm.decline()
        advanceUntilIdle()
        assertEquals(LinkConfirmUiState.Declined("민지"), vm.uiState.value)
        assertEquals(LinkStatus.REVOKED, family.links.single().status)
    }

    @Test
    fun deviceDeletedByServerEndsRequest() = runTest(main.dispatcher) {
        family.links += link("p1", LinkStatus.PENDING)
        val confirming = confirmVm()
        val declining = confirmVm()
        advanceUntilIdle()
        family.fail["confirm:p1"] = DeviceNotRegisteredException()
        confirming.confirm()
        advanceUntilIdle()
        assertEquals(LinkConfirmUiState.Gone, confirming.uiState.value)
        family.fail["revoke:p1"] = DeviceNotRegisteredException()
        declining.decline()
        advanceUntilIdle()
        assertEquals(LinkConfirmUiState.Declined("민지"), declining.uiState.value)
    }

    @Test
    fun declineWhileConfirmIsSendingIsIgnored() = runTest(main.dispatcher) {
        family.links += link("p1", LinkStatus.PENDING)
        val vm = confirmVm()
        advanceUntilIdle()
        family.gate["confirm:p1"] = CompletableDeferred()
        vm.confirm()
        runCurrent()
        vm.decline()
        family.gate.remove("confirm:p1")!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(LinkConfirmUiState.Connected("민지"), vm.uiState.value)
        assertTrue(family.calls.none { it.startsWith("revoke") })
    }

    @Test
    fun declineNetworkFailureKeepsRequest() = runTest(main.dispatcher) {
        family.links += link("p1", LinkStatus.PENDING)
        val vm = confirmVm()
        advanceUntilIdle()
        family.fail["revoke:p1"] = IOException("offline")
        vm.decline()
        advanceUntilIdle()
        assertEquals(FamilyError.NETWORK, (vm.uiState.value as LinkConfirmUiState.Ready).error)
        assertTrue(notifier.cancelledLinks.isEmpty())
    }

    @Test
    fun confirmServerAnswersMapToScreens() = runTest(main.dispatcher) {
        for ((error, expected) in listOf(
            apiError(409, "link-revoked") to LinkConfirmUiState.Gone,
            apiError(404) to LinkConfirmUiState.Gone,
        )) {
            family.links.clear()
            family.links += link("p1", LinkStatus.PENDING)
            val vm = confirmVm()
            advanceUntilIdle()
            family.fail["confirm:p1"] = error
            vm.confirm()
            advanceUntilIdle()
            assertEquals(expected, vm.uiState.value)
        }
    }

    @Test
    fun confirmNetworkFailureKeepsRequestAndAllowsRetry() = runTest(main.dispatcher) {
        family.links += link("p1", LinkStatus.PENDING)
        val vm = confirmVm()
        advanceUntilIdle()
        family.fail["confirm:p1"] = IOException("offline")
        vm.confirm()
        advanceUntilIdle()
        val failed = vm.uiState.value as LinkConfirmUiState.Ready
        assertEquals(FamilyError.NETWORK, failed.error)
        assertEquals(false, failed.busy)
        assertTrue(notifier.cancelledLinks.isEmpty())
        vm.confirm()
        advanceUntilIdle()
        assertEquals(LinkConfirmUiState.Connected("민지"), vm.uiState.value)
    }

    @Test
    fun confirmLoadFailureCanRetry() = runTest(main.dispatcher) {
        family.links += link("p1", LinkStatus.PENDING)
        family.fail["links"] = IOException("offline")
        val vm = confirmVm()
        advanceUntilIdle()
        assertEquals(LinkConfirmUiState.LoadFailed(FamilyError.NETWORK), vm.uiState.value)
        vm.load()
        advanceUntilIdle()
        assertTrue(vm.uiState.value is LinkConfirmUiState.Ready)
    }

    // S5 가족 연결 관리

    @Test
    fun linksShowActiveAndPendingOldestFirst() = runTest(main.dispatcher) {
        family.links += link("b", LinkStatus.ACTIVE, createdAt = 2)
        family.links += link("r", LinkStatus.REVOKED, createdAt = 1)
        family.links += link("a", LinkStatus.PENDING, createdAt = 1)
        val vm = FamilyLinksViewModel(family)
        vm.refresh()
        advanceUntilIdle()
        val ready = vm.uiState.value as FamilyLinksUiState.Ready
        assertEquals(listOf("a", "b"), ready.links.map { it.linkId })
        assertTrue(ready.canAdd)
    }

    @Test
    fun threeConnectedGuardiansIsTheLimit() = runTest(main.dispatcher) {
        repeat(3) { family.links += link("a$it", LinkStatus.ACTIVE, createdAt = it.toLong()) }
        family.links += link("p", LinkStatus.PENDING, createdAt = 9)
        val vm = FamilyLinksViewModel(family)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(false, (vm.uiState.value as FamilyLinksUiState.Ready).canAdd)
        // 확인 대기는 세지 않는다 (서버와 같게)
        family.links.removeAt(0)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(true, (vm.uiState.value as FamilyLinksUiState.Ready).canAdd)
    }

    @Test
    fun revokeRemovesLinkAndSaysSo() = runTest(main.dispatcher) {
        family.links += link("a", LinkStatus.ACTIVE, name = "민지")
        val vm = FamilyLinksViewModel(family)
        vm.refresh()
        advanceUntilIdle()
        val target = (vm.uiState.value as FamilyLinksUiState.Ready).links.single()
        vm.revoke(target)
        vm.revoke(target)
        advanceUntilIdle()
        val ready = vm.uiState.value as FamilyLinksUiState.Ready
        assertTrue(ready.links.isEmpty())
        assertEquals(RevokeMessage.Revoked("민지"), ready.message)
        assertNull(ready.revoking)
        assertEquals(1, family.calls.count { it == "revoke:a" })
    }

    @Test
    fun revokeMessageClearsOnNextRefresh() = runTest(main.dispatcher) {
        family.links += link("a", LinkStatus.ACTIVE)
        family.links += link("b", LinkStatus.ACTIVE, createdAt = 1)
        val vm = FamilyLinksViewModel(family)
        vm.refresh()
        advanceUntilIdle()
        vm.revoke(family.links.first())
        advanceUntilIdle()
        assertTrue((vm.uiState.value as FamilyLinksUiState.Ready).message is RevokeMessage.Revoked)
        vm.refresh()
        advanceUntilIdle()
        val ready = vm.uiState.value as FamilyLinksUiState.Ready
        assertNull(ready.message)
        assertEquals(listOf("b"), ready.links.map { it.linkId })
    }

    @Test
    fun revokeAfterServerDeletedDeviceEmptiesList() = runTest(main.dispatcher) {
        family.links += link("a", LinkStatus.ACTIVE)
        family.links += link("b", LinkStatus.ACTIVE, createdAt = 1)
        val vm = FamilyLinksViewModel(family)
        vm.refresh()
        advanceUntilIdle()
        family.fail["revoke:a"] = DeviceNotRegisteredException()
        vm.revoke(family.links.first())
        advanceUntilIdle()
        assertTrue((vm.uiState.value as FamilyLinksUiState.Ready).links.isEmpty())
    }

    @Test
    fun revokeFailureKeepsLink() = runTest(main.dispatcher) {
        family.links += link("a", LinkStatus.ACTIVE)
        val vm = FamilyLinksViewModel(family)
        vm.refresh()
        advanceUntilIdle()
        family.fail["revoke:a"] = IOException("offline")
        vm.revoke(family.links.single())
        advanceUntilIdle()
        val ready = vm.uiState.value as FamilyLinksUiState.Ready
        assertEquals(listOf("a"), ready.links.map { it.linkId })
        assertEquals(RevokeMessage.Failed(FamilyError.NETWORK), ready.message)
    }

    @Test
    fun revokeOfAlreadyGoneLinkCountsAsRevoked() = runTest(main.dispatcher) {
        family.links += link("a", LinkStatus.ACTIVE)
        val vm = FamilyLinksViewModel(family)
        vm.refresh()
        advanceUntilIdle()
        family.fail["revoke:a"] = apiError(404)
        vm.revoke(family.links.single())
        advanceUntilIdle()
        assertTrue((vm.uiState.value as FamilyLinksUiState.Ready).links.isEmpty())
    }

    @Test
    fun linksLoadFailureShowsErrorButKeepsShownList() = runTest(main.dispatcher) {
        family.fail["links"] = IOException("offline")
        val vm = FamilyLinksViewModel(family)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(FamilyLinksUiState.Failed(FamilyError.NETWORK), vm.uiState.value)
        family.links += link("a", LinkStatus.ACTIVE)
        vm.refresh()
        advanceUntilIdle()
        assertTrue(vm.uiState.value is FamilyLinksUiState.Ready)
        // 이미 보이는 목록은 실패해도 그대로
        family.fail["links"] = IOException("offline")
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf("a"), (vm.uiState.value as FamilyLinksUiState.Ready).links.map { it.linkId })
    }
}
