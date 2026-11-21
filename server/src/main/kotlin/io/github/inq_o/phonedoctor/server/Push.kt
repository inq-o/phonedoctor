package io.github.inq_o.phonedoctor.server

import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import java.time.Duration

/**
 * 기기로 보낼 푸시 하나 (contracts/push-messages.md). 모두 data 메시지이고 알림 표시는 앱이 한다.
 * [data]에는 `v`·`type`이 이미 들어 있다
 */
data class Push(val token: String, val data: Map<String, String>, val high: Boolean, val ttl: Duration)

/** 메시지 종류별 우선순위·보관 시간 (push-messages.md §1·§2) */
enum class PushType(val high: Boolean, val ttl: Duration) {
    LINK_REQUESTED(true, Duration.ofHours(24)),
    LINK_ACTIVATED(true, Duration.ofHours(24)),
    LINK_REVOKED(false, Duration.ofHours(24)),
    ;

    fun to(token: String, vararg data: Pair<String, String>) =
        Push(token, mapOf("v" to "1", "type" to name) + data, high, ttl)
}

/** 푸시 전송. 시험에서는 가짜로 바꾼다 */
fun interface Pusher {
    fun send(push: Push)
}

/** FCM HTTP v1 (Admin SDK) */
class FcmPusher(app: FirebaseApp) : Pusher {
    private val messaging = FirebaseMessaging.getInstance(app)

    override fun send(push: Push) {
        val android = AndroidConfig.builder()
            .setPriority(if (push.high) AndroidConfig.Priority.HIGH else AndroidConfig.Priority.NORMAL)
            .setTtl(push.ttl.toMillis())
            .build()
        messaging.send(Message.builder().setToken(push.token).setAndroidConfig(android).putAllData(push.data).build())
    }
}

/**
 * DB 변경이 커밋된 뒤 보낸다. 실패해도 요청은 성공으로 둔다: 앱이 실행·홈 진입 때 목록을 다시 받아 오므로
 * (push-messages.md §1 누락 대비) 푸시는 빨리 알리는 수단일 뿐이다.
 * 응답 전에 보내는 이유: Cloud Run은 응답 뒤 CPU를 거의 주지 않아 응답 뒤 백그라운드 전송은 늦거나 끊길 수 있음
 */
fun Deps.sendAll(pushes: List<Push>) {
    for (p in pushes) {
        try {
            pusher.send(p)
        } catch (e: Exception) {
            pushLog.warn("푸시 전송 실패 type={}", p.data["type"], e)
        }
    }
}

private val pushLog = org.slf4j.LoggerFactory.getLogger("Push")
