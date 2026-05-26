package com.xixin.lian.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.xixin.lian.R
import com.xixin.lian.network.KtorClient
import com.xixin.lian.store.AppStore
import java.util.regex.Pattern

class PaymentNotificationListener : NotificationListenerService() {

    private val patterns = listOf(
    Pattern.compile("收款\\s*¥?([0-9]+(?:\\.[0-9]{1,2})?)"),
    Pattern.compile("付款\\s*¥?([0-9]+(?:\\.[0-9]{1,2})?)"),
    Pattern.compile("¥\\s*([0-9]+(?:\\.[0-9]{1,2})?)"),
    Pattern.compile("([0-9]+(?:\\.[0-9]{1,2})?)\\s*元")
    )

    private val NOTIFICATION_ID = 999

    override fun onCreate() {
        super.onCreate()
        AppStore.init(this)
        AppStore.addLog("✅ 监听服务进程已创建")
    }

  override fun onListenerConnected() {
    AppStore.addLog(">>> 监控系统成功连接系统通知总线 <<<")
    
    val largeIconBitmap = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)

    val notification = NotificationCompat.Builder(this, AppStore.CHANNEL_MONITOR)
        .setSmallIcon(R.drawable.ic_payment)
        .setLargeIcon(largeIconBitmap)
        .setColor(Color.parseColor("#1E88E5"))
        .setContentTitle("V免签监控运行中")
        .setContentText("正在持续监听微信与支付宝的收款通知")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .build()

    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.notify(NOTIFICATION_ID, notification)
}

    override fun onListenerDisconnected() {
    super.onListenerDisconnected()
    stopForeground(STOP_FOREGROUND_REMOVE)
    AppStore.addLog("⚠️ 监控系统已断开，请检查通知权限")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val content = extras.getString(Notification.EXTRA_TEXT) ?: ""
        val fullText = "$title $content"

        when (pkg) {
            "com.eg.android.AlipayGphone" -> {
                if (fullText.contains("收款") || fullText.contains("付款成功")) {
                    parseAndReport("alipay", fullText)
                }
            }
            "com.tencent.mm" -> {
                if (fullText.contains("微信支付") || fullText.contains("收款") || fullText.contains("转账")) {
                    parseAndReport("wechat", fullText)
                }
            }
        }
    }

    private fun parseAndReport(type: String, text: String) {
        var found = false
        for (pattern in patterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                val amount = matcher.group(1) ?: continue
                AppStore.addLog("💰 捕获到${if(type=="alipay") "支付宝" else "微信"}金额: $amount")
                KtorClient.pushPayment(type, amount)
                found = true
                break
            }
        }
if (!found) {
    AppStore.addLog("⚠️ 未匹配的通知: $text")  // 改这里
}
    }
}