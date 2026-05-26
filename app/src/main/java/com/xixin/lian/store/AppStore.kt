package com.xixin.lian.store

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

object AppStore {
    private const val PREFS_NAME = "v_mq_settings"
    private const val KEY_ENDPOINT = "api_endpoint"
    private const val KEY_SECRET = "secret_key"
    
    // 【关键修复】：更改 Channel ID (加了 _v2 后缀)。
    // 强制系统重新创建一个全新的、重要级别为 DEFAULT 的通知渠道，这样状态栏左上角就必定有图标了！
    const val CHANNEL_MONITOR = "monitor_foreground_v2" 
    const val CHANNEL_PUSH_SUCCESS = "payment_push_success_v2"

    private var prefs: SharedPreferences? = null

    val apiEndpoint = MutableStateFlow("https://")
    val secretKey = MutableStateFlow("123456")

    private val _logs = MutableStateFlow<List<String>>(listOf("系统就绪，等待权限..."))
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    var applicationContext: Context? = null
        private set

    fun init(context: Context) {
        applicationContext = context.applicationContext

        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            apiEndpoint.value = prefs?.getString(KEY_ENDPOINT, "https://") ?: "https://"
            secretKey.value = prefs?.getString(KEY_SECRET, "123456") ?: "123456"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = applicationContext?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            // 监控常驻渠道：使用 IMPORTANCE_DEFAULT 确保状态栏显示图标
            val monitorChannel = NotificationChannel(
                CHANNEL_MONITOR,
                "监控服务 (常驻)",
                NotificationManager.IMPORTANCE_DEFAULT 
            ).apply {
                description = "常驻通知，确保系统不杀后台"
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(monitorChannel)

            // 推送成功渠道：使用 IMPORTANCE_HIGH 确保弹出横幅提示
            val successChannel = NotificationChannel(
                CHANNEL_PUSH_SUCCESS,
                "支付推送成功",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "当支付信息成功推送到服务器时显示横幅" }
            nm.createNotificationChannel(successChannel)
        }
    }

    fun updateEndpoint(url: String) {
        apiEndpoint.value = url
        prefs?.edit()?.putString(KEY_ENDPOINT, url)?.apply()
    }

    fun updateSecret(key: String) {
        secretKey.value = key
        prefs?.edit()?.putString(KEY_SECRET, key)?.apply()
    }

    fun addLog(msg: String) {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val time = sdf.format(Date())
        val current = _logs.value.toMutableList()
        current.add(0, "[$time] $msg")
        if (current.size > 500) current.removeAt(current.size - 1)
        _logs.value = current
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}