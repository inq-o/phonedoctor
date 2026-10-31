package io.github.inq_o.phonedoctor.testapps.usageprobe

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import java.io.File

/**
 * queryEvents 1회마다 files/queries.csv에 조회 전후의 벽시계·elapsed와 앱이 읽은 BOOT_COUNT를 한 줄 덧붙인다.
 * 재부팅·시계 변경 판별(특징 추출기 query_range의 wall·elapsed·boot_count)을 실기기에서 확인하는 용도.
 *
 * - wallAfter·elapsedAfter: queryEvents가 돌아온 직후(이벤트 처리·파일 저장 전)
 * - unlocked: 조회 직전 UserManager.isUserUnlocked. 문서는 잠금 해제 전 조회가 null이라고 하지만 AOSP 15 구현은
 *   서비스가 null을 주면 빈 결과를 돌려주므로(UsageStatsManager.queryEvents), n=0만으로는 잠금 상태와 구분되지 않는다
 * - bootCount: 일반 앱 권한으로 Settings.Global.BOOT_COUNT를 읽은 값. 못 읽으면 비우고 bootCountError에 예외 이름(API 24 미만은 api<24)
 * - n: 받은 이벤트 수(DumpReceiver는 전부, ProbeWorker는 거르기 전 전부). 조회가 null이거나 잠금 상태라 처리하지 않았으면 비움
 *
 * adb exec-out run-as io.github.inq_o.phonedoctor.testapps.usageprobe cat files/queries.csv
 */
object QueryLog {
    class Clock {
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
    }

    fun unlocked(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.N || context.getSystemService(UserManager::class.java).isUserUnlocked

    /** 리시버(메인 스레드)와 작업(작업 스레드)이 같은 파일에 쓰므로 헤더 생성과 행 추가를 함께 잠근다 */
    @Synchronized
    fun append(context: Context, source: String, from: Long, to: Long, before: Clock, after: Clock, unlocked: Boolean, n: Int?) {
        val (boot, err) = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) "" to "api<24" else try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toString() to ""
        } catch (e: Exception) {
            "" to e.javaClass.simpleName
        }
        val out = File(context.filesDir, "queries.csv")
        if (!out.exists()) out.writeText("source,from,to,wallBefore,elapsedBefore,wallAfter,elapsedAfter,unlocked,bootCount,bootCountError,n\n")
        out.appendText("${csv(source)},$from,$to,${before.wall},${before.elapsed},${after.wall},${after.elapsed},$unlocked,$boot,$err,${n ?: ""}\n")
    }

    /** 덤프 파일명은 adb 인자로 오므로 쉼표·따옴표·줄바꿈이 있으면 인용 */
    private fun csv(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${s.replace("\"", "\"\"")}\"" else s
}
