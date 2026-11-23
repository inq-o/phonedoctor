package io.github.inq_o.phonedoctor.core.notifications

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * (보호자) 요청이 바뀌었다는 푸시 신호 (ALERT_*). 푸시 처리는 네트워크를 부르지 않으므로, 열려 있는 화면(G1·G2)이 이것을 보고 다시 읽는다.
 * 화면이 없으면 신호는 버려진다 (화면은 열릴 때 어차피 다시 읽는다)
 */
@Singleton
class RequestChanges @Inject constructor() {
    private val flow = MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 바뀐 요청 ID */
    val changes: SharedFlow<String> = flow.asSharedFlow()

    fun changed(alertId: String) {
        flow.tryEmit(alertId)
    }
}
