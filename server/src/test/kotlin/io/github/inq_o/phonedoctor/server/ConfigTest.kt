package io.github.inq_o.phonedoctor.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ConfigTest {
    private val base = mapOf(
        "DATABASE_URL" to "jdbc:postgresql://h/db", "DATABASE_USER" to "u", "DATABASE_PASSWORD" to "p",
        "FIREBASE_PROJECT_ID" to "p", "FIREBASE_PROJECT_NUMBER" to "1", "APP_CHECK_APP_IDS" to "a, b",
    )

    @Test
    fun `예약 작업 설정은 둘 다 있거나 둘 다 없어야 함`() {
        assertNull(Config.fromEnv(base).schedulerAudience)
        val both = Config.fromEnv(base + mapOf("SCHEDULER_AUDIENCE" to "https://x/internal/tick", "SCHEDULER_SERVICE_ACCOUNT" to "s@x"))
        assertEquals("s@x", both.schedulerServiceAccount)
        assertEquals(setOf("a", "b"), both.appCheckAppIds)
        assertFailsWith<IllegalStateException> { Config.fromEnv(base + ("SCHEDULER_AUDIENCE" to "https://x/internal/tick")) }
        assertFailsWith<IllegalStateException> { Config.fromEnv(base + ("SCHEDULER_SERVICE_ACCOUNT" to "s@x")) }
    }
}
