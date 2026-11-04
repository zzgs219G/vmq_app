package com.xixin.vmq.store

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
import androidx.core.app.NotificationManagerCompat
object AppStore {
    private const val PREFS_NAME = "v_mq_settings"
    private const val KEY_ENDPOINT = "api_endpoint"
    private const val KEY_SECRET = "secret_key"
    private const val KEY_ADMIN_PWD = "admin_password"

    /**
     * 后台密码预填值。
     *
     * ⚠️ 这不是「默认密钥兜底」，二义性要说清：
     *  - 推送密钥 [secretKey] 仍然默认为空 —— 那个值缺失时必须显式报错，
     *    否则会出现「装完即可用、但其实配置错了」的假象（旧版本的坑）。
     *  - 后台密码只是**预填**，用户随时可在设置里改。
     *    它只用于读取订单列表做对账，读不到只会让订单页不可用，
     *    不会影响收款推送与发卡这条主链路。
     *
     * 服务端对应的 ADMIN_PASSWORD 若被轮换，这里也需要同步修改。
     */
    private const val DEFAULT_ADMIN_PASSWORD = "245818"

    // 【关键修复】：更改 Channel ID (加了 _v2 后缀)。
    // 强制系统重新创建一个全新的、重要级别为 DEFAULT 的通知渠道，这样状态栏左上角就必定有图标了！
    const val CHANNEL_MONITOR = "monitor_foreground_v2"
    const val CHANNEL_PUSH_SUCCESS = "payment_push_success_v2"

    private var prefs: SharedPreferences? = null

    // 不做任何默认值兜底：未配置时就是空串，让上层显式报错。
    // 旧版本此处默认给了与后端相同的密钥，会造成“装完即可用”的假象。
    val apiEndpoint = MutableStateFlow("")

    /**
     * 推送签名密钥，对应服务端环境变量 APP_SECRET。
     *
     * ⚠️ 它与 [adminPassword] 是**两个不同的值**，不要混用：
     *  - APP_SECRET     用于收款推送的 HMAC 签名
     *  - ADMIN_PASSWORD 用于管理端接口的鉴权
     * 旧版本只有一个字段同时充当两者，只要后端把这两个值设成不同，
     * 就必然有一半功能失败。
     */
    val secretKey = MutableStateFlow("")

    /** 后台管理密码，对应服务端环境变量 ADMIN_PASSWORD。用于拉取订单列表。 */
    val adminPassword = MutableStateFlow(DEFAULT_ADMIN_PASSWORD)

    private val _logs = MutableStateFlow<List<String>>(listOf("系统就绪，等待权限..."))
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    var applicationContext: Context? = null
        private set

    /**
     * 通知监听权限（NotificationListenerService）是否已授予。
     *
     * ⚠️ 原名 hasNotificationPermission，容易与 POST_NOTIFICATIONS 混淆。
     * 本应用真正依赖的是通知监听权限 —— 没有它完全无法抓取收款通知，
     * 而 POST_NOTIFICATIONS 只影响我们自己的提示能否显示。
     */
    val hasListenerPermission = MutableStateFlow(false)

    /** POST_NOTIFICATIONS（Android 13+）是否已授予，仅影响提示可见性。 */
    val hasNotificationPermission = MutableStateFlow(false)

    fun updatePermission(context: Context) {
        hasListenerPermission.value =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        hasNotificationPermission.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        } else {
            true
        }
    }


    fun init(context: Context) {
        applicationContext = context.applicationContext

        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            apiEndpoint.value = prefs?.getString(KEY_ENDPOINT, "") ?: ""
            secretKey.value = prefs?.getString(KEY_SECRET, "") ?: ""
            adminPassword.value = prefs?.getString(KEY_ADMIN_PWD, DEFAULT_ADMIN_PASSWORD) ?: DEFAULT_ADMIN_PASSWORD
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

    fun updateAdminPassword(pwd: String) {
        adminPassword.value = pwd
        prefs?.edit()?.putString(KEY_ADMIN_PWD, pwd)?.apply()
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

    /**
     * 推送成功后弹提示。
     *
     * 从 KtorClient 抽到此处：KtorClient 不应直接依赖通知构建细节。
     */
    fun notifyPushSuccess(type: String, price: String) {
        try {
            val context = applicationContext ?: return
            val typeName = type.replaceFirstChar { it.uppercase() }
            val builder = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_PUSH_SUCCESS)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("$typeName 支付推送成功")
                .setContentText("金额 $price 元已上报")
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
            androidx.core.app.NotificationManagerCompat.from(context)
                .notify(System.currentTimeMillis().toInt(), builder.build())
        } catch (e: Exception) {
            addLog("⚠️ 通知失败: ${e.message}")
        }
    }
}