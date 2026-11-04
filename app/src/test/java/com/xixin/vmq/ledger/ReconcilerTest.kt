package com.xixin.vmq.ledger

import com.xixin.vmq.network.RemoteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对账逻辑测试。
 *
 * 为什么必须测：对账算错不会崩溃，只会**漏报或误报**。
 * 漏报 = 买家付了钱你永远不知道；误报 = 你被一堆虚假警报淹没，最后不看它了。
 * 两者都让这个功能失去意义。
 */
class ReconcilerTest {

    private val BASE = 1_700_000_000_000L

    private fun txn(
        amountCents: Long,
        occurredAt: Long = BASE,
        direction: String = Txn.Direction.IN,
        status: String = Txn.Status.CONFIRMED,
        source: String = Txn.Source.ALIPAY,
        mergedInto: Long? = null,
        pushState: String = Txn.PushState.NONE,
        linkedOrderId: String? = null
    ) = Txn(
        dedupKey = "k-$amountCents-$occurredAt",
        source = source,
        direction = direction,
        amountCents = amountCents,
        occurredAt = occurredAt,
        createdAt = occurredAt,
        status = status,
        mergedInto = mergedInto,
        pushState = pushState,
        linkedOrderId = linkedOrderId
    )

    private fun order(
        orderId: String = "O1",
        priceYuan: String = "0.01",
        status: String = "paid",
        createTime: Long = BASE,
        expireTime: Long = BASE + 480_000
    ) = RemoteOrder(
        orderId = orderId,
        productName = "测试商品",
        payPrice = priceYuan,
        status = status,
        createTime = createTime,
        payTime = if (status == "paid") createTime + 60_000 else 0,
        expireTime = expireTime,
        hasContact = false
    )

    // ---- 不应误报 ----

    @Test
    fun `收款与已发货订单匹配 - 不报`() {
        val r = Reconciler.reconcile(listOf(txn(1)), listOf(order()))
        assertTrue(r.isEmpty())
    }

    @Test
    fun `已推送成功且关联订单 - 不报`() {
        val r = Reconciler.reconcile(
            listOf(txn(1, pushState = Txn.PushState.SUCCESS, linkedOrderId = "O1")),
            listOf(order(orderId = "O1"))
        )
        assertTrue(r.isEmpty())
    }

    @Test
    fun `支出记录不参与比对`() {
        val r = Reconciler.reconcile(
            listOf(txn(100, direction = Txn.Direction.OUT)),
            emptyList()
        )
        assertTrue(r.isEmpty())
    }

    @Test
    fun `被软合并的记录不参与比对`() {
        val r = Reconciler.reconcile(
            listOf(txn(1, mergedInto = 99)),
            emptyList()
        )
        assertTrue(r.isEmpty())
    }

    @Test
    fun `已忽略的记录不参与比对`() {
        val r = Reconciler.reconcile(
            listOf(txn(1, status = Txn.Status.IGNORED)),
            emptyList()
        )
        assertTrue(r.isEmpty())
    }

    // ---- 应当报警 ----

    @Test
    fun `收到钱但服务端无订单 - 报警且标记 noOrder`() {
        val r = Reconciler.reconcile(listOf(txn(1)), emptyList())
        assertEquals(1, r.size)
        assertTrue(r[0].noOrder)
        assertNull(r[0].matchedOrder)
    }

    @Test
    fun `收到钱但订单仍是 pending - 报警`() {
        val r = Reconciler.reconcile(
            listOf(txn(1)),
            listOf(order(status = "pending"))
        )
        assertEquals(1, r.size)
        assertEquals(false, r[0].noOrder)
        assertEquals(false, r[0].expiredOrder)
    }

    @Test
    fun `收到钱但订单已过期 - 报警且标记 expiredOrder`() {
        val r = Reconciler.reconcile(
            listOf(txn(1)),
            listOf(order(status = "expired"))
        )
        assertEquals(1, r.size)
        assertTrue(r[0].expiredOrder)
    }

    @Test
    fun `金额不匹配 - 报警`() {
        val r = Reconciler.reconcile(
            listOf(txn(1)),           // 0.01
            listOf(order(priceYuan = "0.02"))
        )
        assertEquals(1, r.size)
        assertTrue(r[0].noOrder)
    }

    @Test
    fun `超出时间窗 - 视为无匹配订单`() {
        val tooLate = BASE + 20 * 60 * 1000L   // 20 分钟后才收到通知
        val r = Reconciler.reconcile(
            listOf(txn(1, occurredAt = tooLate)),
            listOf(order(createTime = BASE, expireTime = BASE + 480_000))
        )
        assertEquals(1, r.size)
        assertTrue(r[0].noOrder)
    }

    // ---- candidates 筛选 ----

    @Test
    fun `candidates 排除支出 合并 忽略 与手动补录`() {
        val list = listOf(
            txn(1),
            txn(2, direction = Txn.Direction.OUT),
            txn(3, mergedInto = 1),
            txn(4, status = Txn.Status.IGNORED),
            txn(5, source = Txn.Source.MANUAL)
        )
        val c = Reconciler.candidates(list)
        assertEquals(1, c.size)
        assertEquals(1L, c[0].amountCents)
    }

    @Test
    fun `时间窗内但金额相同的第一笔订单被选中`() {
        val r = Reconciler.reconcile(
            listOf(txn(1)),
            listOf(
                order(orderId = "A", status = "pending"),
                order(orderId = "B", status = "paid")
            )
        )
        // 首个金额匹配的订单是 A（pending），因此判为待付款
        assertEquals(1, r.size)
        assertEquals("A", r[0].matchedOrder?.orderId)
    }
}
