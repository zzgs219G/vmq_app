package com.xixin.lian.network

import android.util.Log
import androidx.core.app.NotificationCompat
import com.xixin.lian.store.AppStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.Dns
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
object KtorClient {
    private val failedTasks = ConcurrentLinkedQueue<Pair<String, String>>()
    private val isPushing = AtomicBoolean(false)

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

    // 主入口：入队后触发消费
    fun pushPayment(type: String, price: String) {
        failedTasks.add(type to price)
        drainQueue()
    }

    // 串行消费队列，用 AtomicBoolean 保证同时只有一个协程在跑
    private fun drainQueue() {
        if (!isPushing.compareAndSet(false, true)) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                while (true) {
                    val task = failedTasks.poll() ?: break
                    doPush(task.first, task.second)
                }
            } finally {
                isPushing.set(false)
                // 收尾时队列如果又进来了新任务，再起一轮
                if (failedTasks.isNotEmpty()) drainQueue()
            }
        }
    }

    private suspend fun doPush(type: String, price: String) {
        try {
            val url = AppStore.apiEndpoint.value.trim()
            val key = AppStore.secretKey.value.trim()
            val sign = hmacSha256("$type$price", key)
            val jsonBody = """{"type":"$type","price":"$price","sign":"$sign"}"""
            AppStore.addLog("⏳ 正在推送($type): $price 元")
            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(jsonBody)
            }
            if (response.status.value in 200..299) {
                val resBody = response.bodyAsText()
                AppStore.addLog("✅ 服务端返回: $resBody")
                sendSuccessNotification(type, price, resBody)
            } else {
                throw Exception("HTTP ${response.status.value}")
            }
        } catch (e: Exception) {
            AppStore.addLog("🚨 推送失败: ${e.message}，已存入重试队列")
            failedTasks.add(type to price) // 失败重新入队，等下次 pushPayment 触发时一起带走
        }
    }

    private fun sendSuccessNotification(type: String, price: String, responseBody: String) {
        try {
            val context = AppStore.applicationContext ?: return
            val typeName = type.replaceFirstChar { it.uppercase() }
            val builder = NotificationCompat.Builder(context, AppStore.CHANNEL_PUSH_SUCCESS)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("$typeName 支付推送成功")
                .setContentText("金额 $price 元已上报")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
            androidx.core.app.NotificationManagerCompat.from(context)
                .notify(System.currentTimeMillis().toInt(), builder.build())
        } catch (e: Exception) {
            AppStore.addLog("⚠️ 通知失败: ${e.message}")
        }
    }
}