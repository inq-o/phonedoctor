package io.github.inq_o.phonedoctor.family

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import io.github.inq_o.phonedoctor.core.data.FamilyAlertSync
import io.github.inq_o.phonedoctor.core.data.FamilySyncScheduler
import io.github.inq_o.phonedoctor.core.data.PushTokenSync
import io.github.inq_o.phonedoctor.core.model.FamilyDecision
import io.github.inq_o.phonedoctor.core.notifications.FamilyPushHandler
import io.github.inq_o.phonedoctor.core.notifications.Notifier
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/** 가족 연결 푸시 (push-messages.md). 받은 즉시 알림만 띄우고 네트워크는 부르지 않는다 */
@AndroidEntryPoint
class FamilyMessagingService : FirebaseMessagingService() {
    @Inject
    lateinit var pushTokenSync: PushTokenSync

    @Inject
    lateinit var pushHandler: FamilyPushHandler

    @Inject
    lateinit var alertSync: FamilyAlertSync

    @Inject
    lateinit var familySync: FamilySyncScheduler

    @Inject
    lateinit var notifier: Notifier

    // 서비스는 이 함수가 끝나면 바로 멈출 수 있어 여기서 보내지 않고 WorkManager에 맡긴다(망이 되면 보내고, 실패하면 다시 시도)
    override fun onNewToken(token: String) = pushTokenSync.schedule(token)

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["v"] == "1" && data["type"] == "DECISION_MADE") {
            onDecision(data)
            return
        }
        pushHandler.handle(data)
    }

    /**
     * (시니어) 보호자가 답함. 기기 안 사본에 반영(DB만, 네트워크 없음)한 뒤 알린다.
     * 이 함수는 백그라운드 스레드에서 불리고 끝나면 서비스가 멈출 수 있어 반영이 끝날 때까지 기다린다
     */
    private fun onDecision(data: Map<String, String>) {
        val alertId = data["alertId"]?.takeIf { it.isNotBlank() } ?: return
        val decision = data["decision"] ?: return
        val applied = runBlocking { alertSync.applyDecision(alertId, decision, data["guardianName"], null) } ?: return
        if (applied.notify) notifier.notifyDecision(applied.alert, applied.decision, applied.guardianName)
        // 괜찮다고 했으면 "허용함" 결과를 서버에 알린다
        if (applied.decision == FamilyDecision.ALLOW) familySync.schedule()
    }
}
