package io.github.inq_o.phonedoctor.family

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import io.github.inq_o.phonedoctor.core.data.PushTokenSync
import io.github.inq_o.phonedoctor.core.notifications.FamilyPushHandler
import javax.inject.Inject

/** 가족 연결 푸시 (push-messages.md). 받은 즉시 알림만 띄우고 네트워크는 부르지 않는다 */
@AndroidEntryPoint
class FamilyMessagingService : FirebaseMessagingService() {
    @Inject
    lateinit var pushTokenSync: PushTokenSync

    @Inject
    lateinit var pushHandler: FamilyPushHandler

    // 서비스는 이 함수가 끝나면 바로 멈출 수 있어 여기서 보내지 않고 WorkManager에 맡긴다(망이 되면 보내고, 실패하면 다시 시도)
    override fun onNewToken(token: String) = pushTokenSync.schedule(token)

    override fun onMessageReceived(message: RemoteMessage) {
        pushHandler.handle(message.data)
    }
}
