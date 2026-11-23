package io.github.inq_o.phonedoctor.core.data

import io.github.inq_o.phonedoctor.core.datastore.UserPreferences
import io.github.inq_o.phonedoctor.core.network.ServerClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * 오탐 신고를 맡기고 보낼지 정하는 한 곳 (US7). 맡기기·보내기와 끄기·모든 데이터 지우기가 겹치지 않게 한 줄로 세운다.
 *
 * - 끄거나 지우면 세대를 올린다. 그 전에 시작한 응답(서버 응답을 기다리는 중)이나 예약한 작업은 세대가 달라 보내지 않는다
 * - 방침: 답한 뒤 1~24시간 사이에 보낸다. 마감은 서버가 기록한 응답 시각 + 24시간 (앱이 다시 켜져 늦게 맡겨도 늘지 않게)
 */
@Singleton
class FalsePositiveGate @Inject constructor(
    private val preferences: UserPreferences,
    private val reporter: FalsePositiveReporter,
    private val serverClock: ServerClock,
) {
    private val mutex = Mutex()

    /** 시험에서 바꾼다 */
    internal var clock: () -> Long = System::currentTimeMillis
    internal var random: Random = Random.Default

    suspend fun generation(): Long = preferences.falsePositiveGeneration.first()

    /** 지금을 서버 시계로 */
    fun serverNow(): Long = clock() + (serverClock.offsetMillis() ?: 0L)

    /**
     * 모든 데이터 지우기: 세대를 올려 이미 맡긴 것과 응답을 기다리는 중인 것의 신고를 모두 무효로.
     * 보내는 중이던 한 건은 끝난 뒤에 무효가 된다 (자물쇠 순서: 그 한 건은 지우기 전에 보낸 것으로 친다)
     */
    suspend fun invalidate() = mutex.withLock {
        preferences.bumpFalsePositiveGeneration()
        reporter.cancelAll()
    }

    /** 끄기: 끄기와 세대 올리기를 한 번에 저장하고 맡긴 것을 거둔다 */
    suspend fun disable() = mutex.withLock {
        preferences.disableFalsePositiveFeedback()
        reporter.cancelAll()
    }

    /**
     * 신고 하나를 맡긴다. [generation]은 응답을 보내기 전에 읽은 세대, [decidedAt]은 서버의 응답 시각(서버 시계, ms).
     * 마감은 서버 시계로 잰 뒤 기기 시계로 옮긴다(서버 응답의 Date로 잰 차이). 그 사이 무효가 됐거나, 이미 맡겼거나,
     * 마감 안에 1시간 넘게 늦춰 보낼 수 없으면 맡기지 않는다
     */
    suspend fun schedule(id: String, report: FalsePositive, generation: Long, decidedAt: Long): Boolean = mutex.withLock {
        if (!sendable(generation)) return@withLock false
        val deadline = decidedAt + WINDOW_MS - (serverClock.offsetMillis() ?: 0L)
        val latest = deadline - clock() - MARGIN_MS
        if (latest <= MIN_DELAY_MS) return@withLock false
        if (!preferences.markFalsePositiveReported(id)) return@withLock false
        reporter.schedule(id, report, generation, deadline, random.nextLong(MIN_DELAY_MS, latest))
        true
    }

    /** 보낼 때 (작업): 세대·설정·지우는 중·마감을 다시 보고, 보내는 동안에는 끄기·지우기가 기다린다. 보냈으면 true */
    suspend fun send(generation: Long, deadline: Long, block: suspend () -> Unit): Boolean = mutex.withLock {
        if (!sendable(generation) || clock() > deadline) return@withLock false
        block()
        true
    }

    private suspend fun sendable(generation: Long): Boolean =
        preferences.falsePositiveFeedback.first() && !preferences.deletionPending.first() && generation() == generation

    companion object {
        const val WINDOW_MS = 24 * 60 * 60 * 1000L
        const val MIN_DELAY_MS = 60 * 60 * 1000L

        /** 마감 전 여유 (재시도·대기 버킷) */
        const val MARGIN_MS = 60 * 60 * 1000L
    }
}
