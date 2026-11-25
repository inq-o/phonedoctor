package io.github.inq_o.phonedoctor.core.data

import androidx.annotation.WorkerThread
import io.github.inq_o.phonedoctor.core.datastore.UserPreferences
import io.github.inq_o.phonedoctor.core.model.PHONE_NAME_MAX
import io.github.inq_o.phonedoctor.core.model.PhoneNameLookup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * (보호자) 상대 폰 표시 이름: 연결 ID → 보호자가 고른 이름 (ux-flows §2, O-G4·G4).
 * 이 폰에만 저장하고 서버로 보내지 않는다. 모든 데이터 지우기 때 함께 지운다
 */
interface PhoneNameRepository : PhoneNameLookup {
    val chosenNames: Flow<Map<String, String>>

    /** [name]이 비었으면 지운다. [PHONE_NAME_MAX]자까지 */
    suspend fun setChosenName(linkId: String, name: String?)
}

internal class DefaultPhoneNameRepository @Inject constructor(
    private val preferences: UserPreferences,
) : PhoneNameRepository {
    override val chosenNames: Flow<Map<String, String>> = preferences.phoneNames

    override suspend fun setChosenName(linkId: String, name: String?) =
        preferences.setPhoneName(linkId, name?.trim()?.take(PHONE_NAME_MAX))

    /** 알림을 만들 때(FCM 수신 스레드) 부른다. DataStore 한 번 읽기라 짧지만 막는 호출이라 메인 스레드에서 부르지 않는다 */
    @WorkerThread
    override fun chosenName(linkId: String): String? = runBlocking { preferences.phoneNames.first()[linkId] }
}
