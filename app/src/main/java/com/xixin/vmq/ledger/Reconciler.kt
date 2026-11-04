package com.xixin.vmq.ledger

import com.xixin.vmq.network.RemoteOrder
import com.xixin.vmq.network.TxnParserCents

/**
 * 对账：把「本地账本记到的收款」与「服务端订单」两边数据比对。
 *
 * ## 为什么需要这个
 *
 * 系统的正常流程是「收到收款通知 → 推送 → 服务端按金额匹配订单 → 发卡」。
 * 这条链路任何一环失败（网络断、服务端 5xx、订单已过期），
 * 买家付了钱却拿不到卡密，而**服务端那边这笔订单仍然是 pending**，
 * 与「买家还没扫码付款」的订单长得一模一样，无法区分。
 *
 * 唯一能区分的信息在设备端：账本里记着「我确实收到了这笔钱」。
 * 把两边一比对，「有收款、无对应已发货订单」的就是疑似漏发。
 *
 * ## 匹配规则
 *
 * 按「金额 + 时间窗」匹配，因为金额本身就是这套系统的订单标识
 * （下单时在商品价上叠加了 ±0.50 元的随机浮动）。
 *
 * 时间窗取订单创建时间前后各 [MATCH_WINDOW_MS]：
 * 收款通知必然发生在订单创建之后，但系统时钟可能有偏差、
 * 通知也可能延迟，所以留出余量。
 */
object Reconciler {

    /**
     * 收款与订单的时间容差。
     *
     * 订单有效期是 8 分钟（后端锁单），前台展示 5 分钟。
     * 收款只可能发生在「订单创建 → 订单过期」之间，因此匹配窗口取 10 分钟足够覆盖。
     */
    private const val MATCH_WINDOW_MS = 10 * 60 * 1000L

    /**
     * 疑似漏发的一条记录。
     *
     * @param txn 本地账本里的收款记录
     * @param matchedOrder 金额+时间能对上的订单（可能为 null，表示服务端完全没有这笔订单）
     */
    data class Suspicious(
        val txn: Txn,
        val matchedOrder: RemoteOrder?
    ) {
        /** 服务端连订单都没有：可能是订单创建失败，或金额完全不匹配 */
        val noOrder: Boolean get() = matchedOrder == null

        /** 订单存在但已过期：买家付款太晚，需要退款或人工处理 */
        val expiredOrder: Boolean get() = matchedOrder?.isExpired == true
    }

    /**
     * 执行比对。
     *
     * @param txns 本地账本记录（通常传已确认的收入记录）
     * @param orders 服务端订单
     * @return 疑似漏发的记录，按时间倒序
     */
    fun reconcile(txns: List<Txn>, orders: List<RemoteOrder>): List<Suspicious> {
        val result = ArrayList<Suspicious>()

        // 已被服务端订单覆盖的（paid 且有对应订单）不需要提示
        val paidOrders = orders.filter { it.isPaid }

        for (txn in txns) {
            // 只关心「钱进来了」的记录
            if (txn.direction != Txn.Direction.IN) continue
            if (txn.mergedInto != null) continue
            if (txn.status == Txn.Status.IGNORED) continue

            // 已经知道对应哪笔订单且已发货 → 正常，跳过
            if (txn.pushState == Txn.PushState.SUCCESS && txn.linkedOrderId != null) {
                val linked = orders.firstOrNull { it.orderId == txn.linkedOrderId }
                if (linked != null && linked.isPaid) continue
            }

            // 按金额 + 时间窗找候选订单
            val candidate = orders.firstOrNull { order ->
                order.payPriceCents == txn.amountCents &&
                    txn.occurredAt >= order.createTime - MATCH_WINDOW_MS &&
                    txn.occurredAt <= order.expireTime + MATCH_WINDOW_MS
            }

            if (candidate != null && candidate.isPaid) {
                // 对上了且已发货 → 正常
                continue
            }

            result.add(Suspicious(txn, candidate))
        }

        return result.sortedByDescending { it.txn.occurredAt }
    }

    /**
     * 从账本记录中筛出「应该参与比对」的那些。
     *
     * 排除：忽略的、被合并的、支出、以及本就是手动补录的（manual 不来自通知，
     * 不代表服务端一定该有订单）。
     */
    fun candidates(txns: List<Txn>): List<Txn> = txns.filter {
        it.direction == Txn.Direction.IN &&
            it.mergedInto == null &&
            it.status != Txn.Status.IGNORED &&
            it.source != Txn.Source.MANUAL
    }
}
