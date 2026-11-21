package io.github.inq_o.phonedoctor.server

import kotlin.test.Test
import kotlin.test.assertFails

/** 계약 대조기가 실제로 어긋남을 잡는지 (통과만 보면 검사기가 아무것도 안 해도 모름) */
class ContractTest {
    @Test
    fun `필수 필드 누락·열거값 밖·형식 위반을 잡음`() {
        val ok = """{"deviceId":"d","role":"protected","displayName":"부모님","notifyCaution":false,"createdAt":"2026-11-21T03:00:00Z"}"""
        Contract.check("Device", ok)
        assertFails { Contract.check("Device", ok.replace(""""deviceId":"d",""", "")) }
        assertFails { Contract.check("Device", ok.replace("protected", "admin")) }
        assertFails { Contract.check("Device", ok.replace("2026-11-21T03:00:00Z", "어제")) }
        assertFails { Contract.check("Problem", """{"type":"not a uri","title":"t","status":400}""") }
    }
}
