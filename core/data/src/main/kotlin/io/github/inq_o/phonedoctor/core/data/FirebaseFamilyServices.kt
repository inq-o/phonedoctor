package io.github.inq_o.phonedoctor.core.data

import android.content.Context
import android.os.Build
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import java.io.IOException
import java.util.Locale
import javax.inject.Inject

internal class FirebaseFamilyAccount @Inject constructor() : FamilyAccount {
    private val auth get() = FirebaseAuth.getInstance()

    override val isSignedIn: Boolean get() = auth.currentUser != null
    override val isAnonymous: Boolean get() = auth.currentUser?.isAnonymous == true

    override suspend fun signInAnonymously() {
        try {
            auth.signInAnonymously().await()
        } catch (e: FirebaseNetworkException) {
            // 화면이 서버 통신 실패와 같은 "인터넷 연결" 안내를 하게 IOException으로 바꾼다
            throw IOException(e)
        }
    }

    override suspend fun signInWithGoogle(idToken: String) {
        try {
            auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
        } catch (e: FirebaseNetworkException) {
            throw IOException(e)
        }
    }

    override suspend fun reauthenticateWithGoogle(idToken: String) {
        val user = auth.currentUser ?: return
        try {
            user.reauthenticate(GoogleAuthProvider.getCredential(idToken, null)).await()
        } catch (e: FirebaseNetworkException) {
            throw IOException(e)
        }
    }

    override fun signOut() = auth.signOut()

    override suspend fun deleteUser() {
        // Google 사용자는 로그인한 지 오래되면 FirebaseAuthRecentLoginRequiredException. 화면이 다시 로그인시킨 뒤 다시 부른다
        try {
            auth.currentUser?.delete()?.await()
        } catch (e: FirebaseAuthRecentLoginRequiredException) {
            throw RecentLoginRequiredException()
        } catch (e: FirebaseNetworkException) {
            throw IOException(e)
        }
        auth.signOut()
    }
}

internal class FirebasePushTokens @Inject constructor() : PushTokens {
    private val messaging get() = FirebaseMessaging.getInstance()

    // 자동 발급은 기본으로 꺼 두었다(앱 매니페스트 firebase_messaging_auto_init_enabled). 처음 부를 때 발급된다
    override suspend fun token(): String = messaging.token.await()

    // 켠 값은 SDK가 저장해 다음 실행에도 유지된다. 켜 두면 앱 업데이트·토큰 만료 때 SDK가 새 토큰을 받아 onNewToken으로 알린다
    override fun setAutoRefresh(enabled: Boolean) {
        messaging.isAutoInitEnabled = enabled
    }

    override suspend fun delete() {
        messaging.deleteToken().await()
    }
}

internal class AndroidDeviceInfo @Inject constructor(@ApplicationContext private val context: Context) : DeviceInfo {
    override val appVersionCode: Int
        get() {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
        }
    override val sdkInt: Int get() = Build.VERSION.SDK_INT
    override val manufacturer: String get() = Build.MANUFACTURER
    override val locale: String get() = Locale.getDefault().toLanguageTag()
}
