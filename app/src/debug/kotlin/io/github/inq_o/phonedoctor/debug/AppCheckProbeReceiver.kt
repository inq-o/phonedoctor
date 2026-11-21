package io.github.inq_o.phonedoctor.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/**
 * 시험용 (debug 빌드 전용). 이 기기가 App Check(Play Integrity, Google Play 밖 배포 설정)를 통과하는지 확인한다.
 * 결과는 logcat `PhoneDoctorAppCheck` 태그로 남긴다. 토큰 값은 남기지 않는다.
 *
 * 앱을 먼저 화면에 띄운 뒤 보낸다. 오래 안 쓴 앱(대기 버킷 rare 이하)은 백그라운드에서 망이 막혀 UnknownHostException이 난다.
 *
 * adb shell monkey -p io.github.inq_o.phonedoctor -c android.intent.category.LAUNCHER 1
 * adb shell am broadcast -f 32 -n io.github.inq_o.phonedoctor/.debug.AppCheckProbeReceiver
 * adb logcat -d -s PhoneDoctorAppCheck
 */
class AppCheckProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (FirebaseApp.getApps(context).isEmpty()) {
            Log.w(TAG, "RESULT=NO_FIREBASE google-services.json 없이 빌드됨")
            return
        }
        val pending = goAsync()
        val appCheck = FirebaseAppCheck.getInstance()
        appCheck.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
        appCheck.getAppCheckToken(true)
            .addOnSuccessListener { Log.i(TAG, "RESULT=OK expiresInSec=${(it.expireTimeMillis - System.currentTimeMillis()) / 1000}") }
            .addOnFailureListener { Log.w(TAG, "RESULT=FAIL ${it.javaClass.simpleName}: ${it.message}") }
            .addOnCompleteListener { pending.finish() }
    }

    private companion object {
        const val TAG = "PhoneDoctorAppCheck"
    }
}
