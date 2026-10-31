package io.github.inq_o.phonedoctor.testapps.usageprobe

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import java.io.File

/**
 * queryEvents 1회마다 files/queries.csv에 조회 전후의 벽시계·elapsed와 앱이 읽은 BOOT_COUNT를 한 줄 덧붙인다.
 * 재부팅·시계 변경 판별(특징 추출기 query_range의 wall·elapsed·boot_count)을 실기기에서 확인하는 용도.
 *
 * - bootCount: 일반 앱 권한으로 Settings.Global.BOOT_COUNT를 읽은 값. 못 읽으면 비우고 bootCountError에 예외 이름(API 24 미만은 api<24)
 * - n: 받은 이벤트 수(DumpReceiver는 전부, ProbeWorker는 거르기 전 전부). 조회가 null이면(잠금 해제 전 등) 비움
 *
 * adb exec-out run-as io.github.inq_o.phonedoctor.testapps.usageprobe cat files/queries.csv
 */
object QueryLog {
    class Clock {
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
    }

    fun append(context: Context, source: String, from: Long, to: Long, before: Clock, n: Int?) {
        val after = Clock()
        val (boot, err) = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) "" to "api<24" else try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toString() to ""
        } catch (e: Exception) {
            "" to e.javaClass.simpleName
        }
        val out = File(context.filesDir, "queries.csv")
        if (!out.exists()) out.writeText("source,from,to,wallBefore,elapsedBefore,wallAfter,elapsedAfter,bootCount,bootCountError,n\n")
        out.appendText("$source,$from,$to,${before.wall},${before.elapsed},${after.wall},${after.elapsed},$boot,$err,${n ?: ""}\n")
    }
}
