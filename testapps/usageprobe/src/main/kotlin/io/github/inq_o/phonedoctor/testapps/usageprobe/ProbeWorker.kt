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
 * 조회 전후 시각·BOOT_COUNT는 queries.csv(QueryLog, source=work). 잠금 해제 전이거나 조회가 null이면 결과를 처리하지 않고
 * 커서를 옮기지 않는다(잠금 상태 조회는 빈 결과로 올 수 있어 "이벤트 없음"으로 읽으면 안 됨).
 */
class ProbeWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences("cursor", Context.MODE_PRIVATE)
        val unlocked = QueryLog.unlocked(ctx)
        val before = QueryLog.Clock()
        val now = before.wall
        val from = prefs.getLong("last", now - 3_600_000)
        val seen = prefs.getStringSet("seenAtLast", emptySet())!!
        val out = File(ctx.filesDir, "work.csv")
        if (!out.exists()) out.writeText("runAt,timeStamp,package,class\n")
        val events: UsageEvents? = ctx.getSystemService(UsageStatsManager::class.java).queryEvents(from, now)
        val after = QueryLog.Clock()
        if (events == null || !unlocked) {
            QueryLog.append(ctx, "work", from, now, before, after, unlocked, null)
            Log.i("USAGEPROBE", "WORK run=$now skipped null=${events == null} unlocked=$unlocked from=$from")
            return Result.success()
        }
        val e = UsageEvents.Event()
        var last = from
        val atLast = mutableSetOf<String>()
        var n = 0
        var all = 0
        val sb = StringBuilder()
        while (events.getNextEvent(e)) {
            all++
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
        QueryLog.append(ctx, "work", from, now, before, after, unlocked, all)
        Log.i("USAGEPROBE", "WORK run=$now n=$n from=$from")
        return Result.success()
    }
}
