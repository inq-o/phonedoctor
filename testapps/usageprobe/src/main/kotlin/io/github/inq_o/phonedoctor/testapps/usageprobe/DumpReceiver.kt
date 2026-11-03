package io.github.inq_o.phonedoctor.testapps.usageprobe

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * [from, to) 구간의 사용 기록 이벤트를 전부 CSV로 쓴다.
 *
 * adb shell am broadcast -n io.github.inq_o.phonedoctor.testapps.usageprobe/.DumpReceiver \
 *   --el from <epochMs> --el to <epochMs> --es out e1.csv
 * adb exec-out run-as io.github.inq_o.phonedoctor.testapps.usageprobe cat files/e1.csv
 * 조회 전후 시각·BOOT_COUNT는 files/queries.csv(QueryLog, source=dump:<out>). 조회가 null이면 헤더만 쓴다
 */
class DumpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val unlocked = QueryLog.unlocked(context)
        val before = QueryLog.Clock()
        val now = before.wall
        val from = intent.getLongExtra("from", now - 3_600_000)
        val to = intent.getLongExtra("to", now)
        val out = File(context.filesDir, intent.getStringExtra("out") ?: "events.csv")
        val events: UsageEvents? = context.getSystemService(UsageStatsManager::class.java).queryEvents(from, to)
        val after = QueryLog.Clock()
        val e = UsageEvents.Event()
        var n = 0
        out.bufferedWriter().use { w ->
            w.write("queriedAt,timeStamp,type,typeName,package,class\n")
            while (events?.getNextEvent(e) == true) {
                w.write("$now,${e.timeStamp},${e.eventType},${TYPE_NAMES[e.eventType] ?: ""},${e.packageName},${e.className ?: ""}\n")
                n++
            }
        }
        QueryLog.append(context, "dump:${out.name}", from, to, before, after, unlocked, if (events == null) null else n)
        Log.i("USAGEPROBE", "DUMP n=${if (events == null) "null" else n} unlocked=$unlocked from=$from to=$to out=${out.name}")
    }

    companion object {
        /** UsageEvents.Event의 공개 int 상수 이름 (숨겨진 종류는 숫자만 남음) */
        val TYPE_NAMES: Map<Int, String> = UsageEvents.Event::class.java.fields
            .filter { it.type == Int::class.javaPrimitiveType && it.name.all { c -> c.isUpperCase() || c == '_' } }
            .filterNot { it.name.startsWith("MOVE_TO_") } // ACTIVITY_RESUMED·PAUSED와 같은 값의 옛 이름
            .associate { it.getInt(null) to it.name }
    }
}
