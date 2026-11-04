package com.xixin.vmq.network

import com.xixin.vmq.store.AppStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.ConnectionPool
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** 推送结果。retryable 区分「值得重试」与「重试也没用」 */
sealed interface PushResult {
    data class Success(val body: String, val orderId: String?) : PushResult
    data class Failure(val reason: String, val retryable: Boolean = true) : PushResult
    /** 未配置地址或密钥，属用户未设置，不算失败 */
    data object NotConfigured : PushResult
}

object KtorClient {

    private data class Task(
        val type: String,
        val price: String,
        val txnId: Long,
        val attempt: Int = 0
    )

    private const val MAX_ATTEMPTS = 5
    private const val BASE_BACKOFF_MS = 2_000L
    private const val MAX_BACKOFF_MS = 60_000L

    private val failedTasks = ConcurrentLinkedQueue<Task>()

    /**
     * 串行消费队列。
     *
     * 用 Mutex 而非 AtomicBoolean：后者在 finally 里 set(false) 与检查队列非空之间
     * 存在窗口，其他线程可插入再起一个消费协程，串行保证失效。
     */
    private val drainMutex = Mutex()

    /** 长驻作用域，避免每次推送都新建 CoroutineScope */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = HttpClient(OkHttp) {
        expectSuccess = false
        engine {
            config {
                connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
                retryOnConnectionFailure(true)
                connectTimeout(10, TimeUnit.SECONDS)
                readTimeout(10, TimeUnit.SECONDS)
            }
        }
    }

    private const val UA = "VMQ-Monitor/2.0 (Android)"

    private fun hmacSha256(data: String, key: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        return mac.doFinal(data.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    fun pushPayment(
        type: String,
        price: String,
        txnId: Long = 0L,
        onResult: ((PushResult) -> Unit)? = null
    ) {
        failedTasks.add(Task(type, price, txnId))
        scope.launch { drain(onResult) }
    }

    /**
     * 失败不在循环里立即重试 —— 那会变成无限空转，每秒向服务端发几十个请求，
     * 触发 Cloudflare 速率限制后连本来能成功的请求也失败。
     * 改为指数退避 + 次数上限。
     */
    private suspend fun drain(onResult: ((PushResult) -> Unit)?) {
        drainMutex.withLock {
            while (true) {
                val task = failedTasks.poll() ?: break
                val result = doPush(task)

                when (result) {
                    is PushResult.Success -> {
                        AppStore.addLog("✅ 服务端返回: ${result.body}")
                        AppStore.notifyPushSuccess(task.type, task.price)
                        runCatching { onResult?.invoke(result) }
                    }

                    is PushResult.Failure -> {
                        val next = task.attempt + 1
                        when {
                            !result.retryable -> {
                                AppStore.addLog("⛔ 推送放弃（不可重试）: ${result.reason}")
                                runCatching { onResult?.invoke(result) }
                            }
                            next >= MAX_ATTEMPTS -> {
                                AppStore.addLog("❌ 推送最终失败（已重试 $MAX_ATTEMPTS 次）: ${result.reason}")
                                runCatching { onResult?.invoke(result) }
                            }
                            else -> {
                                val wait = backoffMs(next)
                                AppStore.addLog(
                                    "⏳ 推送失败: ${result.reason}，${wait / 1000}s 后第 $next 次重试"
                                )
                                scheduleRetry(task.copy(attempt = next), wait, onResult)
                            }
                        }
                    }

                    PushResult.NotConfigured -> {
                        AppStore.addLog("❌ 推送中止：请在设置中填写 API 地址与推送密钥")
                        runCatching { onResult?.invoke(result) }
                    }
                }
            }
        }
    }

    private fun backoffMs(attempt: Int): Long {
        val exp = BASE_BACKOFF_MS shl (attempt - 1).coerceIn(0, 5)
        return exp.coerceAtMost(MAX_BACKOFF_MS)
    }

    private fun scheduleRetry(task: Task, delayMs: Long, onResult: ((PushResult) -> Unit)?) {
        scope.launch {
            delay(delayMs)
            failedTasks.add(task)
            drain(onResult)
        }
    }

    private suspend fun doPush(task: Task): PushResult {
        val url = AppStore.apiEndpoint.value.trim()
        val key = AppStore.secretKey.value.trim()

        if (url.isEmpty() || key.isEmpty()) return PushResult.NotConfigured
        if (!url.startsWith("https://")) {
            return PushResult.Failure("API 地址必须以 https:// 开头（当前: $url）", retryable = false)
        }

        return try {
            val sign = hmacSha256("${task.type}${task.price}", key)
            val jsonBody = JSONObject()
                .put("type", task.type)
                .put("price", task.price)
                .put("sign", sign)
                .toString()

            AppStore.addLog(
                "⏳ 正在推送(${task.type}): ${task.price} 元" +
                    if (task.attempt > 0) "（第 ${task.attempt + 1} 次）" else ""
            )

            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.UserAgent, UA)
                setBody(jsonBody)
            }

            val status = response.status.value
            val resBody = response.bodyAsText()

            if (status !in 200..299) {
                return PushResult.Failure(
                    "HTTP $status${if (resBody.isNotBlank()) " · ${resBody.take(120)}" else ""}",
                    retryable = status == 429 || status >= 500
                )
            }

            // 服务端可能用 HTTP 200 + body.code 表达业务失败，只看状态码会误判成功
            val code = runCatching { JSONObject(resBody).optInt("code", 200) }.getOrDefault(200)
            val msg = runCatching { JSONObject(resBody).optString("msg") }.getOrDefault("")

            if (code != 200) {
                return PushResult.Failure(
                    "业务失败 code=$code${if (msg.isNotBlank()) " · $msg" else ""}",
                    retryable = false
                )
            }

            val orderId = runCatching { JSONObject(resBody).optString("orderId").ifEmpty { null } }
                .getOrNull()
            PushResult.Success(resBody, orderId)
        } catch (e: Exception) {
            PushResult.Failure("${e.javaClass.simpleName}: ${e.message ?: ""}".trim(), retryable = true)
        }
    }

    /** 单次推送，供账本手动重推使用 */
    fun retryOne(type: String, price: String, txnId: Long, onResult: (PushResult) -> Unit) {
        scope.launch {
            val result = doPush(Task(type, price, txnId))
            runCatching { onResult(result) }
        }
    }
}
