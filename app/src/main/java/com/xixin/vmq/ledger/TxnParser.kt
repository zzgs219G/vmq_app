package com.xixin.vmq.ledger

import java.security.MessageDigest
import java.util.Locale

/**
 * 通知文本解析规则。
 *
 * ## 设计原则
 *
 * 1. **宁可判为 UNKNOWN，也不要猜错**。分不清收支方向的，进待确认队列让用户点一下。
 *    猜错的代价是账目错误且用户不知情；判为 UNKNOWN 的代价只是用户多点一次。
 *
 * 2. **强信号优先**。先匹配明确表达方向和金额的句式，再用宽松规则兜底。
 *
 * 3. **旧实现的 bug**：原代码 `if (fullText.contains("转账"))` 直接当收入，
 *    但「转账」既可能是"收到转账"（收入）也可能是"你已转账"（支出），
 *    这会导致用户付钱给他人时被记成收入。本实现必须区分。
 */
object TxnParser {

    /** 解析结果 */
    data class Parsed(
        val amountCents: Long,
        val direction: String,
        /** 命中的规则名，便于排障与规则迭代 */
        val rule: String
    )

    /**
     * 金额正则。
     *
     * 注意：`\s*` 不能省 —— 通知文本里「收款 ¥0.01」和「收款0.01」两种都有。
     * 金额部分要求最多两位小数，避免把「订单号 202601011234」这类长数字当金额。
     */
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

    /**
     * 解析一条通知。
     *
     * @param source Txn.Source.ALIPAY / WECHAT
     * @param text 通知标题+正文拼接
     * @return 解析结果；完全找不到金额时返回 null（调用方应记日志后丢弃）
     */
    fun parse(source: String, text: String): Parsed? {
        val normalized = text.replace('\u00A0', ' ').trim()
        if (normalized.isEmpty()) return null

        // ---- 明确的支出句式优先判定 ----
        // 必须先于收入规则：否则「你已转账 ¥100」会被后面的宽泛规则当成收入
        if (source == Txn.Source.WECHAT) {
            RE_TRANSFER_OUT.find(normalized)?.let { m ->
                return Parsed(toCents(m.groupValues[1]), Txn.Direction.OUT, "wechat.transfer_out")
            }
        }
        listOf(RE_PAID, RE_PAID_WORD).firstNotNullOfOrNull { it.find(normalized) }?.let { m ->
            return Parsed(toCents(m.groupValues[1]), Txn.Direction.OUT, "paid")
        }

        // ---- 退款：钱回来了，按收入处理 ----
        RE_REFUND.find(normalized)?.let { m ->
            return Parsed(toCents(m.groupValues[1]), Txn.Direction.IN, "refund")
        }

        // ---- 明确的收入句式 ----
        listOf(RE_RECEIVE, RE_ARRIVED, RE_RECEIVED_MARK, RE_TRANSFER_IN)
            .firstNotNullOfOrNull { it.find(normalized) }?.let { m ->
                return Parsed(toCents(m.groupValues[1]), Txn.Direction.IN, "receive")
            }

        // ---- 兜底：只认得出金额，不认方向 ----
        // 原实现用「¥金额」和「金额元」两条宽正则直接当收入 —— 太危险：
        // 微信群消息、公众号推文、账单提醒都能命中。这里降级为 UNKNOWN。
        RE_YUAN.find(normalized)?.let { m ->
            val amt = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return null
            return Parsed(toCents(amt), Txn.Direction.UNKNOWN, "amount_only")
        }

        return null
    }

    /** 元字符串 → 整数分。用字符串运算避免浮点误差。 */
    fun toCents(yuan: String): Long {
        val s = yuan.trim()
        if (s.isEmpty()) return 0L
        val dot = s.indexOf('.')
        return if (dot < 0) {
            s.toLongOrNull()?.times(100) ?: 0L
        } else {
            val intPart = s.substring(0, dot).ifEmpty { "0" }
            val fracRaw = s.substring(dot + 1)
            val frac = fracRaw.padEnd(2, '0').take(2)
            (intPart.toLongOrNull() ?: 0L) * 100 + (frac.toLongOrNull() ?: 0L)
        }
    }

    /** 整数分 → 元字符串 */
    fun toYuan(cents: Long): String = "%.2f".format(Locale.US, cents / 100.0)
}

/**
 * 去重规则。
 *
 * ## 为什么必须去重
 *
 * 微信一笔收款常常产生**多条**通知：
 *   1. 微信支付服务号：「微信支付收款0.01元」
 *   2. 微信主程序：「已收款 ¥0.01」
 * 不去重就会双倍记账，而且是**系统性翻倍**，越用账越离谱。
 *
 * ## 为什么不能只做"文本完全相同"去重
 *
 * 上面两条通知的文本**并不相同**，但它们是同一笔钱。
 * 所以除了硬指纹（拦完全重复的投递），还需要"软合并"：
 *   同来源 + 同金额 + 时间窗内 → 判为同一笔
 *
 * ## 为什么时间窗要 90 秒
 *
 * 实测两条通知通常相差 1~3 秒。90 秒是留足余量的折中：
 * 太短会漏合并（还是双倍记账），太长会把真实的两笔同额交易误合并。
 * 误合并比漏合并**更安全**，因为合并是可撤销的（保留原始记录），
 * 而双倍记账会污染所有统计。
 *
 * ## 为什么不能直接删除重复项
 *
 * 极少数情况真的会连续收到两笔相同金额（客户手抖付两次）。
 * 所以是"软合并"：标记 `mergedInto`，不计入统计，但原始记录保留、可撤销。
 */
object DedupRules {

    /** 软合并时间窗：90 秒 */
    const val MERGE_WINDOW_MS = 90_000L

    /**
     * 硬指纹：完全相同的通知视为重复投递。
     *
     * 不包含时间戳 —— 同一笔通知被系统重投递时内容完全一致，应当归为同一条。
     */
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
