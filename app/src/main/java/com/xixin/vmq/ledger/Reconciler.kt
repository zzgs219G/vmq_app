package com.xixin.vmq.ledger

import com.xixin.vmq.network.RemoteOrder
import com.xixin.vmq.network.TxnParserCents

/**
 * 对账：比对本地账本与服务端订单，找出「已收款但未发货」。
 *
 * 服务端无法区分「买家没付」与「付了但推送失败」——两者都是 pending。
 * 唯一能区分的信息在设备端账本里。详细设计与局限见 docs/对账说明.md
 */
object Reconciler {

    /**
     * 收款与订单的时间容差。
     * 订单有效期 8 分钟，收款只可能发生在创建到过期之间，10 分钟足够覆盖并容忍时钟偏差。
     */
    private const val MATCH_WINDOW_MS = 10 * 60 * 1000L

    /**
     * @param matchedOrder 金额与时间能对上的订单；null 表示服务端完全没有这笔订单
     */
    data class Suspicious(
        val txn: Txn,
        val matchedOrder: RemoteOrder?
    ) {
        /** 服务端查不到对应订单：订单创建失败或金额不符 */
        val noOrder: Boolean get() = matchedOrder == null

        /** 订单存在但已过期：买家付款太晚，需退款或人工处理 */
        val expiredOrder: Boolean get() = matchedOrder?.isExpired == true
    }

    fun reconcile(txns: List<Txn>, orders: List<RemoteOrder>): List<Suspicious> {
        val result = ArrayList<Suspicious>()

        for (txn in txns) {
            if (txn.direction != Txn.Direction.IN) continue
            if (txn.mergedInto != null) continue
            if (txn.status == Txn.Status.IGNORED) continue

            // 已推送成功且能对上已发货订单：闭环，跳过
            if (txn.pushState == Txn.PushState.SUCCESS && txn.linkedOrderId != null) {
                val linked = orders.firstOrNull { it.orderId == txn.linkedOrderId }
                if (linked != null && linked.isPaid) continue
            }

            val candidate = orders.firstOrNull { order ->
                order.payPriceCents == txn.amountCents &&
                    txn.occurredAt >= order.createTime - MATCH_WINDOW_MS &&
                    txn.occurredAt <= order.expireTime + MATCH_WINDOW_MS
            }

            if (candidate != null && candidate.isPaid) continue

            result.add(Suspicious(txn, candidate))
        }

        return result.sortedByDescending { it.txn.occurredAt }
    }

    /**
     * 筛出应参与比对的记录。
     * 排除支出、软合并、已忽略，以及手动补录（不来自通知，服务端不一定该有订单）。
     */
    fun candidates(txns: List<Txn>): List<Txn> = txns.filter {
        it.direction == Txn.Direction.IN &&
            it.mergedInto == null &&
            it.status != Txn.Status.IGNORED &&
            it.source != Txn.Source.MANUAL
    }

    /**
     * 在服务端订单里找「这笔收款已经被发过货」的证据。
     *
     * ## 用途
     *
     * 双设备时，慢的那台推送会失败（404/409），但钱确实收到了、货也确实发了。
     * 用这个方法把这类记录从「疑似漏发」里摘出去，避免横幅长期挂着假警报。
     *
     * ## 判据为什么必须收得很紧
     *
     * 这里只做「销账」，不影响钱是否真的到账，所以宁可漏判也不要误判：
     * 误判 = 真漏发的单被当成已发货，那笔钱就彻底没人管了。
     * 因此要求金额精确相等、订单状态必须是 paid，且收款时间落在订单
     * 创建到过期之间 —— 比 [reconcile] 的正常匹配窗口更严格，不留 ±容差。
     */
    fun findFulfilledOrder(txn: Txn, orders: List<RemoteOrder>): RemoteOrder? =
        orders.firstOrNull { order ->
            order.isPaid &&
                order.payPriceCents == txn.amountCents &&
                txn.occurredAt >= order.createTime &&
                txn.occurredAt <= order.expireTime
        }
}
