package io.github.inq_o.phonedoctor.sync.work

import androidx.work.ExistingWorkPolicy
import org.junit.Test
import kotlin.test.assertEquals

class FamilySyncPolicyTest {
    private val now = 1_000_000_000L

    @Test
    fun `기다리는 작업이 없으면 새로 넣는다`() {
        assertEquals(ExistingWorkPolicy.KEEP, familySyncPolicy(urgent = true, running = false, nextRunAt = null, now = now))
    }

    @Test
    fun `급하면 재시도 대기를 풀고 바로`() {
        assertEquals(ExistingWorkPolicy.REPLACE, familySyncPolicy(urgent = true, running = false, nextRunAt = now + 60_000, now = now))
    }

    @Test
    fun `주기 검사는 짧은 재시도 대기를 그대로 둔다 (서버를 두드리지 않게)`() {
        assertEquals(ExistingWorkPolicy.KEEP, familySyncPolicy(urgent = false, running = false, nextRunAt = now + MAX_FAMILY_SYNC_WAIT_MS, now = now))
    }

    @Test
    fun `주기 검사도 대기가 너무 길면 다시 시작한다`() {
        assertEquals(ExistingWorkPolicy.REPLACE, familySyncPolicy(urgent = false, running = false, nextRunAt = now + MAX_FAMILY_SYNC_WAIT_MS + 1, now = now))
    }

    @Test
    fun `도는 중이면 끊지 않는다`() {
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, familySyncPolicy(urgent = true, running = true, nextRunAt = null, now = now))
        assertEquals(ExistingWorkPolicy.KEEP, familySyncPolicy(urgent = false, running = true, nextRunAt = null, now = now))
    }
}
