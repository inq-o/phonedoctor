package io.github.inq_o.phonedoctor.core.network

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck

/** 가족 연결(서버 통신) 켜짐 여부와 시작 준비 */
object FamilyFeature {
    /** 이 빌드에서 가족 연결을 쓰는지. 출시 빌드는 개인정보처리방침을 고치기 전까지 꺼 둔다 (build.gradle.kts) */
    const val ENABLED: Boolean = BuildConfig.FAMILY_ENABLED

    /**
     * 앱 프로세스가 뜰 때(Application.onCreate) 한 번 부른다. App Check 공급자를 설치해 이후 요청에 토큰이 붙게 한다.
     * 설치만 하고 토큰은 첫 요청 때 받는다(연결 전 서버 전송 없음). 꺼진 빌드이거나 Firebase 설정(google-services.json) 없이 빌드했으면 아무것도 안 한다
     */
    fun install(context: Context) {
        if (!ENABLED || FirebaseApp.getApps(context).isEmpty()) return
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(appCheckProviderFactory())
    }
}
