package io.github.inq_o.phonedoctor.core.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.inq_o.phonedoctor.core.usage.detector.Detector
import io.github.inq_o.phonedoctor.core.usage.detector.UsageEvent
import javax.inject.Inject

/** 조회 직전에 잰 기기 시각. 재부팅·시계 변경 판별에 쓴다 */
data class DeviceClock(val wall: Long, val elapsed: Long, val bootCount: Int?, val unlocked: Boolean)

interface UsageEventsSource {
    fun clock(): DeviceClock

    /**
     * [begin, end)의 사용 기록 중 판정기가 쓰는 종류만 OS가 돌려준 순서대로. 조회가 null이면 null.
     * 사용 기록 접근이 꺼져 있으면 SecurityException.
     */
    fun query(begin: Long, end: Long): List<UsageEvent>?
}

class AndroidUsageEventsSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : UsageEventsSource {
    override fun clock() = DeviceClock(
        wall = System.currentTimeMillis(),
        elapsed = SystemClock.elapsedRealtime(),
        // 일반 앱 권한으로 읽는다. 못 읽으면 null로 두고 elapsed·STARTUP으로 재부팅을 판별
        bootCount = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrNull(),
        // 잠금 해제 전 조회는 빈 결과로 올 수 있어 "이벤트 없음"과 구분하려고 따로 본다
        unlocked = context.getSystemService(UserManager::class.java).isUserUnlocked,
    )

    override fun query(begin: Long, end: Long): List<UsageEvent>? {
        val events: UsageEvents = context.getSystemService(UsageStatsManager::class.java).queryEvents(begin, end) ?: return null
        val out = ArrayList<UsageEvent>()
        val e = UsageEvents.Event()
        while (events.getNextEvent(e)) {
            if (e.eventType in Detector.RELEVANT_TYPES) {
                out += UsageEvent(e.timeStamp, e.eventType, e.packageName ?: "", e.className ?: "")
            }
        }
        return out
    }
}
