package io.github.inq_o.phonedoctor.core.data

import android.content.Context
import android.os.Build
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import java.util.Locale
import javax.inject.Inject

internal class FirebaseFamilyAccount @Inject constructor() : FamilyAccount {
    private val auth get() = FirebaseAuth.getInstance()

    override val isSignedIn: Boolean get() = auth.currentUser != null
    override val isAnonymous: Boolean get() = auth.currentUser?.isAnonymous == true

    override suspend fun signInAnonymously() {
        auth.signInAnonymously().await()
    }

    override suspend fun deleteUser() {
        // Google 사용자는 로그인한 지 오래되면 FirebaseAuthRecentLoginRequiredException. 화면이 다시 로그인시킨 뒤 다시 부른다
        auth.currentUser?.delete()?.await()
        auth.signOut()
    }
}

internal class FirebasePushTokens @Inject constructor() : PushTokens {
    // 자동 발급은 꺼 두었다(앱 매니페스트 firebase_messaging_auto_init_enabled). 처음 부를 때 발급된다
    override suspend fun token(): String = FirebaseMessaging.getInstance().token.await()
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
