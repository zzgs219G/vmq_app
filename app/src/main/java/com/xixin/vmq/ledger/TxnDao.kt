package com.xixin.vmq.ledger

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TxnDao {

    @Insert
    suspend fun insert(txn: Txn): Long

    @Update
    suspend fun update(txn: Txn)

    @Query("SELECT * FROM txns WHERE dedupKey = :key LIMIT 1")
    suspend fun findByDedupKey(key: String): Txn?

    @Query("SELECT * FROM txns WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): Txn?

    /**
     * 软合并候选：同来源、同金额、同方向、时间窗内、未被合并过的已入账/待确认记录。
     *
     * 用于识别「微信支付服务号 + 微信主程序」这类重复通知。
     */
    @Query(
        """
        SELECT * FROM txns
        WHERE source = :source
          AND amountCents = :amountCents
          AND direction = :direction
          AND mergedInto IS NULL
          AND occurredAt BETWEEN :from AND :to
        ORDER BY occurredAt ASC
        LIMIT 1
        """
    )
    suspend fun findMergeCandidate(
        source: String,
        amountCents: Long,
        direction: String,
        from: Long,
        to: Long
    ): Txn?

    /** 全部流水（倒序），含已合并的 */
    @Query("SELECT * FROM txns ORDER BY occurredAt DESC")
    fun observeAll(): Flow<List<Txn>>

    /** 待确认队列 */
    @Query("SELECT * FROM txns WHERE status = 'PENDING' AND mergedInto IS NULL ORDER BY occurredAt DESC")
    fun observePending(): Flow<List<Txn>>

    /** 已入账流水（不含被合并的） */
    @Query("SELECT * FROM txns WHERE status = 'CONFIRMED' AND mergedInto IS NULL ORDER BY occurredAt DESC")
    fun observeConfirmed(): Flow<List<Txn>>

    /** 推送失败的记录 */
    @Query("SELECT * FROM txns WHERE pushState = 'FAILED' ORDER BY occurredAt DESC LIMIT 100")
    fun observePushFailed(): Flow<List<Txn>>

    // ---- 统计：一律在 SQL 里聚合，不把明细拉到内存再累加 ----
    // 单位是分，返回 Long 不会有浮点误差。

    @Query(
        """
        SELECT COALESCE(SUM(amountCents), 0) FROM txns
        WHERE status = 'CONFIRMED' AND mergedInto IS NULL
          AND direction = 'IN' AND occurredAt BETWEEN :from AND :to
        """
    )
    fun sumInBetween(from: Long, to: Long): Flow<Long>

    @Query(
        """
        SELECT COALESCE(SUM(amountCents), 0) FROM txns
        WHERE status = 'CONFIRMED' AND mergedInto IS NULL
          AND direction = 'OUT' AND occurredAt BETWEEN :from AND :to
        """
    )
    fun sumOutBetween(from: Long, to: Long): Flow<Long>

    @Query(
        """
        SELECT COUNT(*) FROM txns
        WHERE status = 'CONFIRMED' AND mergedInto IS NULL
          AND direction = 'IN' AND occurredAt BETWEEN :from AND :to
        """
    )
    fun countInBetween(from: Long, to: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM txns WHERE status = 'PENDING' AND mergedInto IS NULL")
    fun countPending(): Flow<Int>

    @Query("DELETE FROM txns WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM txns WHERE status = 'IGNORED' AND mergedInto IS NOT NULL")
    suspend fun purgeMergedIgnored()
}
