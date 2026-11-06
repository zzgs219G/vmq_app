package com.xixin.vmq.ledger

import java.security.MessageDigest
import java.util.Locale

/**
 * 通知文本解析。规则与取舍见 docs/解析与去重.md
 *
 * 核心原则：宁可判为未知，也不要猜错。分不清收支方向的进待确认队列，
 * 由用户点选 —— 猜错的代价是账目静默变脏，判未知只是多点一次。
 */
object TxnParser {

    data class Parsed(
        val amountCents: Long,
        val direction: String,
        /** 命中的规则名，便于排障与规则迭代 */
        val rule: String
    )

    private val AMOUNT = """(\d+(?:\.\d{1,2})?)"""

    private val RE_RECEIVE = Regex("""收款\s*[¥￥]?\s*$AMOUNT""")
    private val RE_ARRIVED = Regex("""到账\s*[¥￥]?\s*$AMOUNT""")
    private val RE_RECEIVED_MARK = Regex("""已收款\s*[¥￥]?\s*$AMOUNT""")
    private val RE_PAID = Regex("""(?:付款成功|支付成功)\s*[¥￥]?\s*$AMOUNT""")
    private val RE_PAID_WORD = Regex("""付款\s*[¥￥]\s*$AMOUNT""")
    private val RE_TRANSFER_IN = Regex("""收到转账\s*[¥￥]?\s*$AMOUNT""")
    private val RE_TRANSFER_OUT = Regex("""你已转账\s*[¥￥]?\s*$AMOUNT""")
    private val RE_REFUND = Regex("""退款\s*[¥￥]?\s*$AMOUNT""")
    private val RE_YUAN = Regex("""[¥￥]\s*$AMOUNT|$AMOUNT\s*元""")

    fun parse(source: String, text: String): Parsed? {
        val normalized = text.replace('\u00A0', ' ').trim()
        if (normalized.isEmpty()) return null

        // 支出句式必须先判：「你已转账 ¥100」会被后面的收入规则误吃
        if (source == Txn.Source.WECHAT) {
            RE_TRANSFER_OUT.find(normalized)?.let { m ->
                return Parsed(toCents(m.groupValues[1]), Txn.Direction.OUT, "wechat.transfer_out")
            }
        }
        listOf(RE_PAID, RE_PAID_WORD).firstNotNullOfOrNull { it.find(normalized) }?.let { m ->
            return Parsed(toCents(m.groupValues[1]), Txn.Direction.OUT, "paid")
        }

        // 退款：钱回到手里
        RE_REFUND.find(normalized)?.let { m ->
            return Parsed(toCents(m.groupValues[1]), Txn.Direction.IN, "refund")
        }

        listOf(RE_RECEIVE, RE_ARRIVED, RE_RECEIVED_MARK, RE_TRANSFER_IN)
            .firstNotNullOfOrNull { it.find(normalized) }?.let { m ->
                return Parsed(toCents(m.groupValues[1]), Txn.Direction.IN, "receive")
            }

        // 只认得金额、判不出方向：判为 UNKNOWN 而非收入。
        // 旧实现把这类当收入，导致群消息、公众号推文全被误记。
        RE_YUAN.find(normalized)?.let { m ->
            val amt = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return null
            return Parsed(toCents(amt), Txn.Direction.UNKNOWN, "amount_only")
        }

        return null
    }

    /** 元字符串转整数分。用字符串运算，不经过 Double。 */
    fun toCents(yuan: String): Long {
        val s = yuan.trim()
        if (s.isEmpty()) return 0L
        val dot = s.indexOf('.')
        return if (dot < 0) {
            s.toLongOrNull()?.times(100) ?: 0L
        } else {
            val intPart = s.substring(0, dot).ifEmpty { "0" }
            val frac = s.substring(dot + 1).padEnd(2, '0').take(2)
            (intPart.toLongOrNull() ?: 0L) * 100 + (frac.toLongOrNull() ?: 0L)
        }
    }

    fun toYuan(cents: Long): String = "%.2f".format(Locale.US, cents / 100.0)
}

/**
 * 去重规则。设计依据见 docs/解析与去重.md
 *
 * 微信一笔收款常产生多条通知（支付服务号 + 主程序），不去重会系统性双倍记账。
 * 文本不完全相同，所以除硬指纹外还需要按「同来源+同金额+同方向+时间窗」软合并。
 */
object DedupRules {

    /** 软合并时间窗。实测重复通知相差 1~3 秒，90 秒留足余量 */
    const val MERGE_WINDOW_MS = 90_000L

    /**
     * 硬指纹的时间桶宽度。
     *
     * ## 为什么指纹里必须带时间
     *
     * 旧实现只对「包名|标题|通知全文|金额|方向」做 SHA-256，**不含任何时间成分**，
     * 而 dedupKey 上有唯一索引（见 Txn.kt），于是这个判定是永久的。
     *
     * 后果是一次真实的丢单：支付宝的收款通知有时不带时间，
     * 11月5日 19:21 收到的「收款 3.00 元」与 11月6日 18:27 的另一笔金额、
     * 文案完全相同，第二笔被判成「重复通知」直接丢弃 —— 没记账、没推送、
     * 服务端无从得知，买家等不到卡只能来问。账本里也查不到这笔钱。
     *
     * 收款通知撞文案是常态：这个店铺几乎所有商品都是 3 元，
     * 同一金额反复出现，只能靠时间区分。
     *
     * ## 为什么是 10 分钟
     *
     * 真正的重复投递（同一条通知被系统重发）间隔是秒级，10 分钟远大于它。
     * 同时 10 分钟远小于「两笔真实交易可能的最小间隔」在这个场景下的量级
     * （订单有效期本身就是 8 分钟，同一金额在 10 分钟内通常只会成交一笔）。
     *
     * ## 取桶边界撞车了会怎样
     *
     * 若真有一对重复投递正好跨在桶边界（如 18:29:59 与 18:30:01），
     * 硬指纹会算成两个不同的键，但紧接着的**软合并**（同来源+同金额+同方向+90秒）
     * 仍然会把它合并掉。两道防线互补，不会因此双倍记账。
     *
     * 方向相反的取舍是刻意的：漏合并 = 多一条可撤销的记录，
     * 误合并（旧实现）= 钱收了却永远发不出货。详见 docs/解析与去重.md
     */
    const val HARD_KEY_BUCKET_MS = 10 * 60 * 1000L

    /**
     * 硬指纹：同一时间桶内完全相同的通知视为重复投递。
     *
     * @param occurredAt 通知发生时间。必须传入，否则跨天的同文案收款会被永久误判为重复。
     */
    fun hardKey(
        pkg: String,
        title: String,
        text: String,
        amountCents: Long,
        direction: String,
        occurredAt: Long
    ): String {
        val bucket = occurredAt / HARD_KEY_BUCKET_MS
        val raw = "$pkg|$title|$text|$amountCents|$direction|$bucket"
        return sha256(raw)
    }

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
