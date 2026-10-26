package io.github.inq_o.phonedoctor.testapps.usageprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import java.io.File

/**
 * 런처 아이콘이 여는 실제 액티비티(별칭이면 targetActivity)와 시스템 앱 여부를 CSV로 쓴다. 규칙 평가(E3)용.
 *
 * adb shell am broadcast -n io.github.inq_o.phonedoctor.testapps.usageprobe/.LaunchersReceiver
 * adb exec-out run-as io.github.inq_o.phonedoctor.testapps.usageprobe cat files/launchers.csv
 */
class LaunchersReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val out = File(context.filesDir, "launchers.csv")
        val rows = pm.queryIntentActivities(main, PackageManager.MATCH_ALL).map {
            val a = it.activityInfo
            val system = a.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            "${a.packageName},${a.name},${a.targetActivity ?: a.name},$system"
        }
        out.writeText("package,launcherClass,resolvedClass,system\n" + rows.joinToString("\n") + "\n")
        Log.i("USAGEPROBE", "LAUNCHERS n=${rows.size}")
    }
}
