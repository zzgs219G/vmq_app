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

    /** 硬指纹：完全相同的通知视为重复投递 */
    fun hardKey(pkg: String, title: String, text: String, amountCents: Long, direction: String): String {
        val raw = "$pkg|$title|$text|$amountCents|$direction"
        return sha256(raw)
    }

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
