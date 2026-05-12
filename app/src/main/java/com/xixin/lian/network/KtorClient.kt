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

object KtorClient {

    // 离线重试队列，存放发送失败的单子 (type, price)
    private val failedTasks = ConcurrentLinkedQueue<Pair<String, String>>()

    private val client = HttpClient(OkHttp) {
        expectSuccess = false
        engine {
            config {
                // 1. 资源优化：连接池管理，限制 5 个连接，5 分钟空闲释放，防止内存堆积
                connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
                retryOnConnectionFailure(true)
                connectTimeout(10, TimeUnit.SECONDS)
                readTimeout(10, TimeUnit.SECONDS)

                dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        return if (hostname == "faka.ili.asia") {
                            listOf(
                                InetAddress.getByName("104.21.23.50"),
                                InetAddress.getByName("172.67.155.100"),
                                InetAddress.getByName("108.162.192.1")
                            )
                        } else {
                            Dns.SYSTEM.lookup(hostname)
                        }
                    }
                })
            }
        }
    }

    private fun md5(input: String): String {
        return MessageDigest.getInstance("MD5").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    // 主入口：尝试推送，如果失败自动放入重试队列
    fun pushPayment(type: String, price: String) {
        // 先检查队列里有没有之前的漏网之鱼，顺便补发
        processRetryQueue()
        
        doPush(type, price)
    }

    private fun doPush(type: String, price: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = AppStore.apiEndpoint.value.trim()
                val key = AppStore.secretKey.value.trim()
                val sign = md5("$type$price$key")
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
                failedTasks.add(type to price)
            }
        }
    }

    // 尝试补发重试队列里的任务
    private fun processRetryQueue() {
        if (failedTasks.isEmpty()) return
        
        CoroutineScope(Dispatchers.IO).launch {
            val iterator = failedTasks.iterator()
            while (iterator.hasNext()) {
                val task = iterator.next()
                // 这里为了防止并发干扰，先尝试推送，如果还失败就留在队列里
                doPush(task.first, task.second)
                iterator.remove()
            }
        }
    }

    private fun sendSuccessNotification(type: String, price: String, responseBody: String) {
        try {
            val typeName = type.replaceFirstChar { it.uppercase() }
            val builder = NotificationCompat.Builder(AppStore.applicationContext, AppStore.CHANNEL_PUSH_SUCCESS)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("$typeName 支付推送成功")
                .setContentText("金额 $price 元已上报")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)

            androidx.core.app.NotificationManagerCompat.from(AppStore.applicationContext)
                .notify(System.currentTimeMillis().toInt(), builder.build())
        } catch (e: Exception) {
            AppStore.addLog("⚠️ 通知失败: ${e.message}")
        }
    }
}
