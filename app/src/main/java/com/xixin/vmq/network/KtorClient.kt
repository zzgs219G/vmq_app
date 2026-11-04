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

/**
 * 推送结果。
 */
sealed interface PushResult {
    data class Success(val body: String, val orderId: String?) : PushResult

    /**
     * 失败。
     *
     * retryable 区分「值得重试」与「重试也没用」：
     *  - 网络异常 / 超时 / 5xx / 429  → 可重试
     *  - 401 签名错误 / 400 参数错误  → 不可重试（配置问题，重试只会刷日志）
     */
    data class Failure(val reason: String, val retryable: Boolean = true) : PushResult

    /** 未配置 API 地址或密钥，属用户未设置，不算失败 */
    data object NotConfigured : PushResult
}

object KtorClient {

    /**
     * 推送任务。
     *
     * attempt 记录已尝试次数，用于指数退避与上限控制。
     */
    private data class Task(
        val type: String,
        val price: String,
        val txnId: Long,
        val attempt: Int = 0
    )

    /** 单笔任务最多尝试次数。超过后放弃并记录推送失败（账本里可查、可人工重推）。 */
    private const val MAX_ATTEMPTS = 5

    /** 退避基数：2s, 4s, 8s, 16s, 32s（封顶 60s） */
    private const val BASE_BACKOFF_MS = 2_000L
    private const val MAX_BACKOFF_MS = 60_000L

    private val failedTasks = ConcurrentLinkedQueue<Task>()

    /**
     * 串行化消费者。
     *
     * 原实现有两个问题，均已修正：
     *  1. AtomicBoolean + finally 里先 set(false) 再检查队列 —— 两步之间若有其他线程进入
     *     pushPayment→drain，会看到 isPushing==false 再起一个消费协程，串行保证失效。
     *     改用 Mutex。
     *  2. **失败后重新入队、且 while 循环立刻再 poll 出来** —— 无延迟无上限，
     *     变成无限空转：每秒向服务端发几十个请求，触发 Cloudflare 速率限制后
     *     连本来能成功的请求也一起失败。改为止损：退避 + 次数上限。
     */
    private val drainMutex = Mutex()

    /** 长驻作用域：不要每次推送都新建 CoroutineScope */
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

    private fun hmacSha256(data: String, key: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        return mac.doFinal(data.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * 入队并触发消费。
     *
     * @param txnId 账本记录 id；回填推送状态时使用
     * @param onResult 结果回调（IO 线程执行），用于回填账本推送状态
     */
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
     * 消费队列。
     *
     * 关键：失败**不在循环里立即重试**，而是排一个延迟任务后退出循环。
     * 这样既不会空转打爆服务端，也保留了断网恢复后自动补推的能力。
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
                        val nextAttempt = task.attempt + 1
                        if (!result.retryable) {
                            // 配置类错误：重试没有意义，只会在日志里刷屏
                            AppStore.addLog("⛔ 推送放弃（不可重试）: ${result.reason}")
                            runCatching { onResult?.invoke(result) }
                        } else if (nextAttempt >= MAX_ATTEMPTS) {
                            AppStore.addLog(
                                "❌ 推送最终失败（已重试 $MAX_ATTEMPTS 次）: ${result.reason}。" +
                                    "可在「账本」中查看该笔并人工处理"
                            )
                            runCatching { onResult?.invoke(result) }
                        } else {
                            val wait = backoffMs(nextAttempt)
                            AppStore.addLog(
                                "⏳ 推送失败: ${result.reason}，" +
                                    "${wait / 1000}s 后第 $nextAttempt 次重试"
                            )
                            scheduleRetry(task.copy(attempt = nextAttempt), wait, onResult)
                        }
                    }

                    PushResult.NotConfigured -> {
                        AppStore.addLog("❌ 推送中止：请在设置中填写 API 地址与通讯密钥")
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

    private fun scheduleRetry(
        task: Task,
        delayMs: Long,
        onResult: ((PushResult) -> Unit)?
    ) {
        scope.launch {
            delay(delayMs)
            failedTasks.add(task)
            drain(onResult)
        }
    }

    private suspend fun doPush(task: Task): PushResult {
        val url = AppStore.apiEndpoint.value.trim()
        val key = AppStore.secretKey.value.trim()

        // 配置缺失属于「用户没配好」，重试没有意义
        if (url.isEmpty() || key.isEmpty()) return PushResult.NotConfigured
        if (!url.startsWith("https://")) {
            return PushResult.Failure(
                "API 地址必须以 https:// 开头（当前: $url）",
                retryable = false
            )
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
                // 显式 UA：OkHttp 默认 UA 是 "okhttp/4.x"，部分 WAF / Bot Fight 规则
                // 会针对非浏览器 UA 做挑战或拦截，表现为「浏览器正常、App 失败」。
                header(HttpHeaders.UserAgent, "VMQ-Monitor/2.0 (Android)")
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

            // ⚠️ 后端对业务错误（签名校验失败除外）返回的是 HTTP 200 + body 里的 code，
            // 例如 {"code":404,"msg":"无匹配单"}。只看 HTTP 状态会把失败当成功，
            // 表现为「日志显示✅，但买家没收到卡密」。这里必须解 body 判定。
            val code = runCatching { JSONObject(resBody).optInt("code", 200) }.getOrDefault(200)
            val msg = runCatching { JSONObject(resBody).optString("msg") }.getOrDefault("")

            if (code != 200) {
                return PushResult.Failure(
                    "业务失败 code=$code${if (msg.isNotBlank()) " · $msg" else ""}",
                    // 401 签名错误、404 无匹配单都不该重试：
                    // 前者是配置问题，后者说明这笔钱本来就没有对应订单
                    retryable = false
                )
            }

            val orderId = runCatching { JSONObject(resBody).optString("orderId").ifEmpty { null } }
                .getOrNull()
            PushResult.Success(resBody, orderId)
        } catch (e: Exception) {
            PushResult.Failure(
                "${e.javaClass.simpleName}: ${e.message ?: ""}".trim(),
                retryable = true
            )
        }
    }

    /** 单独推一个任务并在结束后回调（供账本手动重推使用） */
    fun retryOne(type: String, price: String, txnId: Long, onResult: (PushResult) -> Unit) {
        scope.launch {
            val result = doPush(Task(type, price, txnId))
            runCatching { onResult(result) }
        }
    }
}
