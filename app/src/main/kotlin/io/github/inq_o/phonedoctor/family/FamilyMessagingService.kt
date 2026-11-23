package io.github.inq_o.phonedoctor.family

import com.google.firebase.messaging.FirebaseMessagingService
import dagger.hilt.android.AndroidEntryPoint
import io.github.inq_o.phonedoctor.core.data.PushTokenSync
import javax.inject.Inject

/** 가족 연결 푸시. 지금은 토큰 갱신만 서버에 알린다. 메시지 종류별 처리는 연결 화면과 함께 (push-messages.md) */
@AndroidEntryPoint
class FamilyMessagingService : FirebaseMessagingService() {
    @Inject
    lateinit var pushTokenSync: PushTokenSync

    // 서비스는 이 함수가 끝나면 바로 멈출 수 있어 여기서 보내지 않고 WorkManager에 맡긴다(망이 되면 보내고, 실패하면 다시 시도)
    override fun onNewToken(token: String) = pushTokenSync.schedule(token)
}
