package com.xixin.vmq.ledger

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一笔流水。
 *
 * ## 为什么金额用 Long 存「分」
 *
 * 整条链路原本是 `price: String` → `parseFloat` → `toFixed(2)`，全程浮点。
 * 单笔展示没问题，但记账要做累加统计，`0.1 + 0.2 != 0.3` 这类误差会累积，
 * 表现为「明细加起来和合计对不上」。所以账本内部统一用整数分存储，
 * 只在展示时转成元。
 *
 * ## 为什么保留 rawText
 *
 * 解析规则一定会出错并被修正（例如把「你已转账」误判成收入）。
 * 保留原始通知文本后，规则迭代可以对历史数据**重新解析**，
 * 而不需要用户手动重输一遍。这是账本可修复性的基础。
 */
@Entity(
    tableName = "txns",
    indices = [
        Index(value = ["occurredAt"]),
        Index(value = ["status"]),
        Index(value = ["dedupKey"], unique = true)
    ]
)
data class Txn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** 去重指纹。见 [DedupRules] */
    @ColumnInfo(name = "dedupKey") val dedupKey: String,

    /** alipay / wechat / manual */
    val source: String,

    /** IN=收入 OUT=支出 UNKNOWN=待确认 */
    val direction: String,

    /** 金额，单位：分。恒为正数，方向由 direction 表达 */
    @ColumnInfo(name = "amountCents") val amountCents: Long,

    /** 通知标题 */
    val title: String = "",

    /** 通知正文 */
    val text: String = "",

    /** 完整原始文本（标题+正文），用于事后重新解析 */
    @ColumnInfo(name = "rawText") val rawText: String = "",

    /** 来源包名 */
    val pkg: String = "",

    /** 交易发生时间（取通知时间） */
    @ColumnInfo(name = "occurredAt") val occurredAt: Long,

    /** 入库时间 */
    @ColumnInfo(name = "createdAt") val createdAt: Long,

    /** PENDING=待确认 CONFIRMED=已入账 IGNORED=已忽略 */
    val status: String = Status.PENDING,

    /** 被软合并到哪一笔（非空表示本条是重复通知，不计入统计但保留可查） */
    @ColumnInfo(name = "mergedInto") val mergedInto: Long? = null,

    /** 关联的发卡订单号（可选） */
    @ColumnInfo(name = "linkedOrderId") val linkedOrderId: String? = null,

    /** 推送状态：NONE=未配置 SUCCESS=已推送 FAILED=推送失败 */
    @ColumnInfo(name = "pushState") val pushState: String = PushState.NONE,

    /** 推送失败原因 */
    @ColumnInfo(name = "pushError") val pushError: String = "",

    /** 用户备注 */
    val note: String = ""
) {
    object Status {
        const val PENDING = "PENDING"
        const val CONFIRMED = "CONFIRMED"
        const val IGNORED = "IGNORED"
    }

    object Direction {
        const val IN = "IN"
        const val OUT = "OUT"
        const val UNKNOWN = "UNKNOWN"
    }

    object Source {
        const val ALIPAY = "alipay"
        const val WECHAT = "wechat"
        const val MANUAL = "manual"
    }

    object PushState {
        const val NONE = "NONE"
        const val SUCCESS = "SUCCESS"
        const val FAILED = "FAILED"
    }

    /** 金额展示：分 → 元，保留两位 */
    val amountYuan: String
        get() = "%.2f".format(amountCents / 100.0)

    /** 是否计入统计（已确认 且 未被合并 且 方向已知） */
    val countable: Boolean
        get() = status == Status.CONFIRMED && mergedInto == null && direction != Direction.UNKNOWN
}
