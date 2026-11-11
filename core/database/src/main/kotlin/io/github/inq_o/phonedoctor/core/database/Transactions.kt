package io.github.inq_o.phonedoctor.core.database

import androidx.room.withTransaction
import javax.inject.Inject

/** 여러 DAO 호출을 한 트랜잭션으로 묶는다 (판정 결과·상태·검사 기록을 함께 확정) */
class DatabaseTransactions @Inject constructor(private val database: AppDatabase) {
    suspend fun <T> run(block: suspend () -> T): T = database.withTransaction(block)
}
