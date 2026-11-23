package io.github.inq_o.phonedoctor.family

import com.google.firebase.messaging.FirebaseMessagingService
import dagger.hilt.android.AndroidEntryPoint
import io.github.inq_o.phonedoctor.core.data.FamilyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 가족 연결 푸시. 지금은 토큰 갱신만 서버에 알린다. 메시지 종류별 처리는 연결 화면과 함께 (push-messages.md) */
@AndroidEntryPoint
class FamilyMessagingService : FirebaseMessagingService() {
    @Inject
    lateinit var family: FamilyRepository

    private val scope = CoroutineScope(SupervisorJob())

    override fun onNewToken(token: String) {
        // 실패해도 다음 등록·갱신 때 새 토큰이 간다. 서비스가 죽지 않게 삼킨다
        scope.launch { runCatching { family.updatePushToken(token) } }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
