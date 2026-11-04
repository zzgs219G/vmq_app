package com.xixin.vmq.ledger

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一笔流水。
 *
 * 金额用 Long 存「分」而非浮点：账本要累加，0.1+0.2!=0.3 的误差会累积成
 * 「明细加起来和合计对不上」。
 *
 * 保留 rawText：解析规则一定会被修正，留着原文才能对历史数据重新解析。
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

    /** 去重指纹 */
    @ColumnInfo(name = "dedupKey") val dedupKey: String,

    val source: String,
    val direction: String,

    /** 金额，单位分。恒为正，方向由 direction 表达 */
    @ColumnInfo(name = "amountCents") val amountCents: Long,

    val title: String = "",
    val text: String = "",

    /** 完整原始文本，用于事后重新解析 */
    @ColumnInfo(name = "rawText") val rawText: String = "",

    val pkg: String = "",

    @ColumnInfo(name = "occurredAt") val occurredAt: Long,
    @ColumnInfo(name = "createdAt") val createdAt: Long,

    /** PENDING / CONFIRMED / IGNORED */
    val status: String = Status.PENDING,

    /** 非空表示本条是重复通知，不计入统计但保留可查 */
    @ColumnInfo(name = "mergedInto") val mergedInto: Long? = null,

    @ColumnInfo(name = "linkedOrderId") val linkedOrderId: String? = null,

    /** NONE / SUCCESS / FAILED */
    @ColumnInfo(name = "pushState") val pushState: String = PushState.NONE,

    @ColumnInfo(name = "pushError") val pushError: String = "",

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

    val amountYuan: String
        get() = "%.2f".format(amountCents / 100.0)

    /** 是否计入统计 */
    val countable: Boolean
        get() = status == Status.CONFIRMED &&
            mergedInto == null &&
            direction != Direction.UNKNOWN
}
