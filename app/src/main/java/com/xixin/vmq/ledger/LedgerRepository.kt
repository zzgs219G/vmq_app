package com.xixin.vmq.ledger

import android.content.Context
import com.xixin.vmq.store.AppStore
import kotlinx.coroutines.flow.Flow

/**
 * 账本仓库：把「收到一条通知」变成「一笔可查的账」。
 *
 * ## 处理流程
 *
 * ```
 * 通知文本
 *   → 解析（金额 + 方向）        TxnParser
 *   → 找不到金额 → 丢弃（记日志）
 *   → 硬指纹去重                 DedupRules.hardKey
 *   → 软合并（90秒窗口）         DedupRules.MERGE_WINDOW_MS
 *   → 入库
 * ```
 *
 * ## 为什么入库先于推送
 *
 * 推送可能失败（网络、服务端故障），但**账必须留下**。
 * 所以 `record()` 只负责记账，推送状态由后续 `markPushSuccess/markPushFailed` 回填。
 * 这样即使推送链路整体不可用，账本依然是完整的。
 */
class LedgerRepository(private val context: Context) {

    private val dao = LedgerDatabase.get(context).txnDao()

    /** 记账结果 */
    sealed interface RecordResult {
        /** 新增了一笔，需要推送 */
        data class Created(val txn: Txn, val id: Long) : RecordResult

        /** 完全重复的通知，已丢弃 */
        data class Duplicate(val existing: Txn) : RecordResult

        /** 软合并到已有记录，不计入统计 */
        data class Merged(val into: Txn, val id: Long) : RecordResult

        /** 解析不出金额 */
        data class Unparsed(val reason: String) : RecordResult
    }

    /**
     * 记录一条通知。
     *
     * @param source Txn.Source.ALIPAY / WECHAT
     */
    suspend fun record(
        source: String,
        pkg: String,
        title: String,
        text: String,
        occurredAt: Long = System.currentTimeMillis()
    ): RecordResult {
        val fullText = "$title $text".trim()
        val parsed = TxnParser.parse(source, fullText)
            ?: return RecordResult.Unparsed("未匹配到金额: $fullText")

        val hardKey = DedupRules.hardKey(pkg, title, text, parsed.amountCents, parsed.direction)

        // 1. 硬指纹：完全相同 → 直接丢弃
        dao.findByDedupKey(hardKey)?.let { return RecordResult.Duplicate(it) }

        // 2. 软合并：同来源+同金额+同方向+时间窗内 → 判为同一笔
        //    方向必须相同，否则「收到 ¥100」和「你已转账 ¥100」会被错误合并
        val candidate = dao.findMergeCandidate(
            source = source,
            amountCents = parsed.amountCents,
            direction = parsed.direction,
            from = occurredAt - DedupRules.MERGE_WINDOW_MS,
            to = occurredAt + DedupRules.MERGE_WINDOW_MS
        )
        if (candidate != null) {
            val merged = Txn(
                dedupKey = hardKey,
                source = source,
                direction = parsed.direction,
                amountCents = parsed.amountCents,
                title = title,
                text = text,
                rawText = fullText,
                pkg = pkg,
                occurredAt = occurredAt,
                createdAt = System.currentTimeMillis(),
                status = Txn.Status.IGNORED,
                mergedInto = candidate.id,
                pushState = Txn.PushState.NONE,
                note = "软合并：与 #${candidate.id} 视为同一笔"
            )
            val id = dao.insert(merged)
            return RecordResult.Merged(candidate, id)
        }

        // 3. 全新一笔
        // 方向不明确的进 PENDING 等用户确认；明确的也先进 PENDING，
        // 由用户确认后才计入统计 —— 通知只产生「待确认」，不假装全自动。
        val txn = Txn(
            dedupKey = hardKey,
            source = source,
            direction = parsed.direction,
            amountCents = parsed.amountCents,
            title = title,
            text = text,
            rawText = fullText,
            pkg = pkg,
            occurredAt = occurredAt,
            createdAt = System.currentTimeMillis(),
            status = Txn.Status.PENDING,
            pushState = Txn.PushState.NONE,
            note = "规则: ${parsed.rule}"
        )
        val id = dao.insert(txn)
        return RecordResult.Created(txn.copy(id = id), id)
    }

    /** 手动补录（通知漏了的情况） */
    suspend fun recordManual(amountCents: Long, direction: String, note: String): Long {
        val now = System.currentTimeMillis()
        val txn = Txn(
            dedupKey = "manual-$now-${amountCents}",
            source = Txn.Source.MANUAL,
            direction = direction,
            amountCents = amountCents,
            title = "手动补录",
            text = note,
            rawText = note,
            pkg = "",
            occurredAt = now,
            createdAt = now,
            status = Txn.Status.CONFIRMED,
            note = note
        )
        return dao.insert(txn)
    }

    suspend fun confirm(id: Long, direction: String? = null) {
        val t = dao.findById(id) ?: return
        dao.update(
            t.copy(
                status = Txn.Status.CONFIRMED,
                direction = direction ?: t.direction
            )
        )
    }

    suspend fun ignore(id: Long) {
        val t = dao.findById(id) ?: return
        dao.update(t.copy(status = Txn.Status.IGNORED))
    }

    suspend fun updateNote(id: Long, note: String) {
        val t = dao.findById(id) ?: return
        dao.update(t.copy(note = note))
    }

    suspend fun delete(id: Long) = dao.deleteById(id)

    suspend fun markPushSuccess(id: Long, orderId: String?) {
        val t = dao.findById(id) ?: return
        dao.update(
            t.copy(
                pushState = Txn.PushState.SUCCESS,
                pushError = "",
                linkedOrderId = orderId ?: t.linkedOrderId
            )
        )
    }

    suspend fun markPushFailed(id: Long, error: String) {
        val t = dao.findById(id) ?: return
        dao.update(t.copy(pushState = Txn.PushState.FAILED, pushError = error))
    }

    // ---- 查询 / 统计 ----

    fun observeAll(): Flow<List<Txn>> = dao.observeAll()
    fun observePending(): Flow<List<Txn>> = dao.observePending()
    fun observeConfirmed(): Flow<List<Txn>> = dao.observeConfirmed()
    fun observePushFailed(): Flow<List<Txn>> = dao.observePushFailed()
    fun countPending(): Flow<Int> = dao.countPending()

    fun sumIn(from: Long, to: Long): Flow<Long> = dao.sumInBetween(from, to)
    fun sumOut(from: Long, to: Long): Flow<Long> = dao.sumOutBetween(from, to)
    fun countIn(from: Long, to: Long): Flow<Int> = dao.countInBetween(from, to)
    suspend fun purgeMergedIgnored() = dao.purgeMergedIgnored()
}
