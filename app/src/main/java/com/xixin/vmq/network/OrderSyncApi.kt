package com.xixin.vmq.network

import android.os.Build
import android.provider.Settings
import com.xixin.vmq.store.AppStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * 一条服务端订单（设备端视图）。
 *
 * 只含比对与展示需要的字段：卡密内容与买家邮箱都不会下发到这里。
 */
data class RemoteOrder(
    val orderId: String,
    val productName: String,
    val payPrice: String,
    val status: String,
    val createTime: Long,
    val payTime: Long,
    val expireTime: Long,
    val hasContact: Boolean
) {
    /** 金额转整数分，避免浮点比较误差 */
    val payPriceCents: Long get() = TxnParserCents.toCents(payPrice)

    val isPending: Boolean get() = status == "pending"
    val isPaid: Boolean get() = status == "paid"
    val isExpired: Boolean get() = status == "expired"
}

/** 供 RemoteOrder 使用的分转换，避免依赖 ledger 包造成循环引用 */
internal object TxnParserCents {
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
}

sealed interface OrdersResult {
    data class Success(val orders: List<RemoteOrder>, val serverTime: Long) : OrdersResult
    data class Failure(val reason: String, val unauthorized: Boolean = false) : OrdersResult
    /** 未配置后台地址或密码 */
    data object NotConfigured : OrdersResult
}

/**
 * 后台订单拉取。
 *
 * ## 鉴权为什么直接复用后台密码
 *
 * 本应用与发卡网是同一套自用系统，多引入一个 DEVICE_TOKEN 只增加维护成本
 * （改一次要同步改 Cloudflare 环境变量、App 设置、以及每台备用机）。
 * 直接用 ADMIN_PASSWORD 走既有 admin 鉴权链路，一条链路、一处修改。
 *
 * ## 设备标识为什么不作为凭证
 *
 * Android ID 不是秘密：任何应用都能读到自己的 ANDROID_ID，改机工具也能改。
 * 因此它只作为「这单是哪台机器推送的」的标识字段，不承担任何鉴权职责。
 */
object OrderSyncApi {

    private val client = HttpClient(OkHttp) {
        expectSuccess = false
        engine {
            config {
                connectionPool(ConnectionPool(2, 1, TimeUnit.MINUTES))
                connectTimeout(15, TimeUnit.SECONDS)
                readTimeout(15, TimeUnit.SECONDS)
            }
        }
    }

    private const val UA = "VMQ-Monitor/2.0 (Android)"

    /** 本机设备标识（仅用于区分主手机 / 备用机，非安全凭证） */
    fun deviceId(): String {
        val ctx = AppStore.applicationContext ?: return "unknown"
        return runCatching {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        }.getOrDefault("unknown")
    }

    /**
     * 由 API 接收地址推导后台根地址。
     *
     * 用户在 App 里填的是推送地址（https://域名/api/push），
     * 订单接口在同一域名下的 /api/admin/orders。这里做一次安全推导：
     * 只接受以 /api/push 结尾的地址，其余情况如实报错，不做猜测。
     */
    fun deriveAdminBase(pushUrl: String): String? {
        val u = pushUrl.trim().removeSuffix("/")
        return if (u.endsWith("/api/push")) u.removeSuffix("/api/push") else null
    }

    /**
     * 拉取订单列表。
     *
     * @param since 仅返回比该时间戳更新的订单；0 表示全量
     */
    suspend fun fetchOrders(since: Long = 0L): OrdersResult = withContext(Dispatchers.IO) {
        val pushUrl = AppStore.apiEndpoint.value.trim()
        val pwd = AppStore.adminPassword.value.trim()

        if (pushUrl.isEmpty() || pwd.isEmpty()) return@withContext OrdersResult.NotConfigured
        if (!pushUrl.startsWith("https://")) {
            return@withContext OrdersResult.Failure("API 地址必须以 https:// 开头")
        }

        val base = deriveAdminBase(pushUrl)
            ?: return@withContext OrdersResult.Failure(
                "API 地址必须以 /api/push 结尾，才能推导出后台地址（当前: $pushUrl）"
            )

        val endpoint = "$base/api/admin/orders"
        try {
            val res = client.get(endpoint) {
                header(HttpHeaders.Authorization, pwd)
                header(HttpHeaders.UserAgent, UA)
                if (since > 0) parameter("since", since)
                parameter("limit", 200)
            }
            val status = res.status.value
            val body = res.bodyAsText()

            if (status == 401) {
                return@withContext OrdersResult.Failure(
                    "后台密码校验失败，请检查设置里的通讯密钥是否与 ADMIN_PASSWORD 一致",
                    unauthorized = true
                )
            }
            if (status == 404) {
                return@withContext OrdersResult.Failure(
                    "订单接口不存在（404）。服务端可能还未部署新版本 /api/admin/orders"
                )
            }
            if (status !in 200..299) {
                return@withContext OrdersResult.Failure("HTTP $status · ${body.take(120)}")
            }

            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: return@withContext OrdersResult.Failure("响应不是合法 JSON")
            val arr = json.optJSONArray("orders")
                ?: return@withContext OrdersResult.Failure("响应缺少 orders 字段")

            val list = ArrayList<RemoteOrder>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(
                    RemoteOrder(
                        orderId = o.optString("orderId"),
                        productName = o.optString("productName"),
                        payPrice = o.optString("payPrice"),
                        status = o.optString("status"),
                        createTime = o.optLong("createTime"),
                        payTime = o.optLong("payTime"),
                        expireTime = o.optLong("expireTime"),
                        hasContact = o.optBoolean("hasContact", false)
                    )
                )
            }
            OrdersResult.Success(list, json.optLong("serverTime", System.currentTimeMillis()))
        } catch (e: Exception) {
            OrdersResult.Failure("${e.javaClass.simpleName}: ${e.message ?: ""}".trim())
        }
    }
}
