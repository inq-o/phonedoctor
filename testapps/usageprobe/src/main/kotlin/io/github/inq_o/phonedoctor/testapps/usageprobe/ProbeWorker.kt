package io.github.inq_o.phonedoctor.testapps.usageprobe

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File

/**
 * 15분 주기 작업 흉내(E4). 실행마다 커서 이후 ACTIVITY_RESUMED를 읽어 work.csv에 덧붙인다.
 * 광고 시각과 이 파일의 runAt을 맞추면 "광고 → 감지" 지연이 나온다. 커서는 data-model `usage_cursor`처럼
 * lastEventTime부터 다시 읽고 겹친 이벤트는 (시각, 패키지, 클래스)로 거른다.
 */
class ProbeWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences("cursor", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val from = prefs.getLong("last", now - 3_600_000)
        val seen = prefs.getStringSet("seenAtLast", emptySet())!!
        val out = File(ctx.filesDir, "work.csv")
        if (!out.exists()) out.writeText("runAt,timeStamp,package,class\n")
        val events = ctx.getSystemService(UsageStatsManager::class.java).queryEvents(from, now)
        val e = UsageEvents.Event()
        var last = from
        val atLast = mutableSetOf<String>()
        var n = 0
        val sb = StringBuilder()
        while (events.getNextEvent(e)) {
            if (e.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
            val key = "${e.timeStamp},${e.packageName},${e.className}"
            if (e.timeStamp == from && key in seen) continue
            sb.append("$now,$key\n")
            n++
            if (e.timeStamp > last) { last = e.timeStamp; atLast.clear() }
            if (e.timeStamp == last) atLast += key
        }
        out.appendText(sb.toString())
        prefs.edit().putLong("last", last).putStringSet("seenAtLast", if (last == from) seen + atLast else atLast).apply()
        File(ctx.filesDir, "runs.csv").appendText("$now,$n\n")
        Log.i("USAGEPROBE", "WORK run=$now n=$n from=$from")
        return Result.success()
    }
}
