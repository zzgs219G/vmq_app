package com.xixin.vmq.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析规则测试。
 *
 * 为什么必须测：解析错了不会崩溃，只会**静默算错账**。
 * 旧实现把「你已转账 ¥100」当成收入，用户付钱给朋友会被记成收了 100 块，
 * 而且不会有任何报错提示。
 */
class TxnParserTest {

    // ---- 收入判定 ----

    @Test
    fun `支付宝收款 - 识别为收入`() {
        val r = TxnParser.parse(Txn.Source.ALIPAY, "支付宝 收款 0.01元")
        assertNotNull(r)
        assertEquals(Txn.Direction.IN, r!!.direction)
        assertEquals(1L, r.amountCents)
    }

    @Test
    fun `支付宝到账 - 识别为收入`() {
        val r = TxnParser.parse(Txn.Source.ALIPAY, "到账 ¥12.34")
        assertEquals(Txn.Direction.IN, r!!.direction)
        assertEquals(1234L, r.amountCents)
    }

    @Test
    fun `微信已收款 - 识别为收入`() {
        val r = TxnParser.parse(Txn.Source.WECHAT, "微信支付 已收款 ¥0.01")
        assertEquals(Txn.Direction.IN, r!!.direction)
        assertEquals(1L, r.amountCents)
    }

    @Test
    fun `收到转账 - 识别为收入`() {
        val r = TxnParser.parse(Txn.Source.WECHAT, "收到转账 ¥100")
        assertEquals(Txn.Direction.IN, r!!.direction)
        assertEquals(10000L, r.amountCents)
    }

    // ---- 支出判定（这是旧实现最大的 bug） ----

    @Test
    fun `你已转账 - 必须识别为支出，不能当收入`() {
        val r = TxnParser.parse(Txn.Source.WECHAT, "你已转账 ¥100")
        assertEquals(Txn.Direction.OUT, r!!.direction)
        assertEquals(10000L, r.amountCents)
    }

    @Test
    fun `付款成功 - 识别为支出`() {
        val r = TxnParser.parse(Txn.Source.ALIPAY, "付款成功 ¥28.50")
        assertEquals(Txn.Direction.OUT, r!!.direction)
        assertEquals(2850L, r.amountCents)
    }

    @Test
    fun `支付成功 - 识别为支出`() {
        val r = TxnParser.parse(Txn.Source.WECHAT, "微信支付 支付成功 ¥5")
        assertEquals(Txn.Direction.OUT, r!!.direction)
        assertEquals(500L, r.amountCents)
    }

    @Test
    fun `退款 - 按收入处理`() {
        val r = TxnParser.parse(Txn.Source.ALIPAY, "退款 ¥15.00")
        assertEquals(Txn.Direction.IN, r!!.direction)
        assertEquals(1500L, r.amountCents)
    }

    // ---- 模糊项必须判 UNKNOWN，不能猜 ----

    @Test
    fun `只有金额没有方向 - 必须为 UNKNOWN`() {
        val r = TxnParser.parse(Txn.Source.WECHAT, "¥9.99")
        assertEquals(Txn.Direction.UNKNOWN, r!!.direction)
        assertEquals(999L, r.amountCents)
    }

    @Test
    fun `群消息里的金额 - 必须为 UNKNOWN 而不是收入`() {
        val r = TxnParser.parse(Txn.Source.WECHAT, "张三：这个 20 元划算")
        assertEquals(Txn.Direction.UNKNOWN, r!!.direction)
    }

    @Test
    fun `没有金额 - 返回 null`() {
        assertNull(TxnParser.parse(Txn.Source.WECHAT, "微信支付 欢迎使用"))
    }

    @Test
    fun `空文本 - 返回 null`() {
        assertNull(TxnParser.parse(Txn.Source.ALIPAY, ""))
    }

    // ---- 金额转换（不能有浮点误差） ----

    @Test
    fun `金额转换 - 整数元`() {
        assertEquals(10000L, TxnParser.toCents("100"))
    }

    @Test
    fun `金额转换 - 一位小数补零`() {
        assertEquals(150L, TxnParser.toCents("1.5"))
    }

    @Test
    fun `金额转换 - 两位小数`() {
        assertEquals(1L, TxnParser.toCents("0.01"))
        assertEquals(2850L, TxnParser.toCents("28.50"))
    }

    @Test
    fun `金额转换 - 浮点累加不产生误差`() {
        // 0.1 + 0.2 用 Double 是 0.30000000000000004
        // 整数分相加必须精确等于 30
        val a = TxnParser.toCents("0.1")
        val b = TxnParser.toCents("0.2")
        assertEquals(30L, a + b)
    }

    @Test
    fun `金额往返转换保持一致`() {
        listOf("0.01", "1.00", "28.50", "999.99").forEach {
            assertEquals(it, TxnParser.toYuan(TxnParser.toCents(it)))
        }
    }

    // ---- 硬指纹的时间桶 ----
    //
    // 这一组防的是一次真实丢单：支付宝通知不带时间时，
    // 隔天同金额同文案的两笔收款算出同一个指纹，第二笔被永久判成重复，
    // 既不记账也不推送，买家付了钱拿不到卡，而站长完全无从得知。

    private fun key(occurredAt: Long) = DedupRules.hardKey(
        pkg = "com.eg.android.AlipayGphone",
        title = "",
        text = "收款 3.00元",
        amountCents = 300L,
        direction = Txn.Direction.IN,
        occurredAt = occurredAt
    )

    private val T0 = 1_760_000_000_000L   // 固定基准时刻

    @Test
    fun `秒级重复投递 - 指纹相同 仍判重复`() {
        assertEquals(key(T0), key(T0 + 3_000L))
    }

    @Test
    fun `同一分钟内重复投递 - 指纹相同`() {
        assertEquals(key(T0), key(T0 + 59_000L))
    }

    @Test
    fun `隔一天的同金额同文案 - 指纹必须不同`() {
        // 这是本次修复的核心：旧实现在这里返回同一个指纹，导致丢单
        assertNotEquals(key(T0), key(T0 + 24 * 60 * 60 * 1000L))
    }

    @Test
    fun `隔一小时的同文案 - 指纹必须不同`() {
        assertNotEquals(key(T0), key(T0 + 60 * 60 * 1000L))
    }

    @Test
    fun `跨桶边界 - 指纹不同 但软合并仍能兜住`() {
        val bucketMs = DedupRules.HARD_KEY_BUCKET_MS
        val beforeBoundary = bucketMs - 1_000L
        val afterBoundary = bucketMs + 1_000L
        // 硬指纹不同（可能被记成两笔）
        assertNotEquals(key(beforeBoundary), key(afterBoundary))
        // 但两笔间隔仅 2 秒，远小于软合并窗口，第二笔会被合并掉，不会双倍记账
        assertTrue(afterBoundary - beforeBoundary < DedupRules.MERGE_WINDOW_MS)
    }

    @Test
    fun `指纹区分金额与方向`() {
        val base = DedupRules.hardKey("p", "", "t", 300L, Txn.Direction.IN, T0)
        assertNotEquals(base, DedupRules.hardKey("p", "", "t", 301L, Txn.Direction.IN, T0))
        assertNotEquals(base, DedupRules.hardKey("p", "", "t", 300L, Txn.Direction.OUT, T0))
        assertNotEquals(base, DedupRules.hardKey("p2", "", "t", 300L, Txn.Direction.IN, T0))
    }
}
