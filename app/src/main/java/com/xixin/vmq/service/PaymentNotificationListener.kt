package com.xixin.vmq.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.xixin.vmq.R
import com.xixin.vmq.keepalive.ListenerStatus
import com.xixin.vmq.keepalive.Watchdog
import com.xixin.vmq.ledger.LedgerRepository
import com.xixin.vmq.ledger.Txn
import com.xixin.vmq.ledger.TxnParser
import com.xixin.vmq.network.KtorClient
import com.xixin.vmq.network.PushResult
import com.xixin.vmq.store.AppStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.regex.Pattern

class PaymentNotificationListener : NotificationListenerService() {

    /**
     * 服务级协程作用域。
     *
     * onNotificationPosted 是同步回调，写数据库必须异步，否则会阻塞通知总线分发。
     * 用 SupervisorJob 保证单笔记账失败不影响后续通知处理。
     */
    private val ledgerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val patterns = listOf(
        Pattern.compile("收款\\s*¥?([0-9]+(?:\\.[0-9]{1,2})?)"),
        Pattern.compile("付款\\s*¥?([0-9]+(?:\\.[0-9]{1,2})?)"),
        Pattern.compile("¥\\s*([0-9]+(?:\\.[0-9]{1,2})?)"),
        Pattern.compile("([0-9]+(?:\\.[0-9]{1,2})?)\\s*元")
    )

    private val NOTIFICATION_ID = 999

    override fun onCreate() {
        super.onCreate()
        // 崩溃捕获必须在最早期注册：注册前的异常抓不到。
        // 同时会初始化 FileLogger，让后续日志可落盘。
        com.xixin.vmq.crash.CrashHandler.init(this)
        AppStore.init(this)
        AppStore.addLog("✅ 监听服务进程已创建")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        AppStore.addLog(">>> 监控系统成功连接系统通知总线 <<<")
        ListenerStatus.markConnected(this)

        // 服务已连上，说明链路恢复：把闹钟链续上，让看门狗继续兜底。
        if (Watchdog.shouldRun(this)) Watchdog.schedule(this)

        showOngoingNotification()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        ListenerStatus.markDisconnected(this)
        AppStore.addLog("⚠️ 监控系统已断开，请检查通知权限")

        // 系统主动解除绑定（权限被撤销 / 服务被冻结后回收）。
        // 若用户仍希望运行，请求系统重新绑定。
        if (Watchdog.shouldRun(this) && ListenerStatus.permissionGranted(this)) {
            runCatching {
                requestRebind(ListenerStatus.componentName(this))
                AppStore.addLog("🔁 已请求系统重新绑定监听服务")
            }
        }
    }

    /**
     * 常驻通知。
     *
     * ⚠️ 这不是前台服务通知（NotificationListenerService 本身由系统绑定，
     * 不需要也无法通过 startForeground 提升优先级）。它的作用有两个：
     *  1. 让用户随时能看到「监控是否在跑」，而不是装完就忘
     *  2. 提供一个快速回到应用界面的入口
     *
     * 旧实现在 onListenerDisconnected 里调用 stopForeground()，
     * 但从未调用 startForeground() —— 那是一次无效调用，已移除。
     */
    private fun showOngoingNotification() {
        val largeIconBitmap = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)

        val openIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            android.content.Intent(this, com.xixin.vmq.MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, AppStore.CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_payment)
            .setLargeIcon(largeIconBitmap)
            .setColor(Color.parseColor("#1E88E5"))
            .setContentTitle("V免签监控运行中")
            .setContentText("正在持续监听微信与支付宝的收款通知")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
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
                AppStore.addLog("💰 捕获到${if (type == "alipay") "支付宝" else "微信"}金额: $amount")
                ListenerStatus.markEvent(this)
                recordAndPush(type, amount, text)
                found = true
                break
            }
        }
        if (!found) {
            AppStore.addLog("⚠️ 未匹配的通知: $text")
        }
    }

    /**
     * 先记账，再推送。
     *
     * 顺序不能反：推送可能失败（网络/服务端故障），但账必须留下。
     * 记账成功后才把推送结果回填到该笔流水，用户就能在账本里看到
     * 「这笔钱收到了，但没推出去」—— 这是自动发卡链路唯一的兜底观测点。
     */
    private fun recordAndPush(source: String, rawAmount: String, fullText: String) {
        val pkg = if (source == "alipay") "com.eg.android.AlipayGphone" else "com.tencent.mm"
        val title = ""
        val body = fullText

        ledgerScope.launch {
            val repo = LedgerRepository(applicationContext)
            val result = runCatching {
                repo.record(
                    source = if (source == "alipay") Txn.Source.ALIPAY else Txn.Source.WECHAT,
                    pkg = pkg,
                    title = title,
                    text = body
                )
            }.getOrElse { e ->
                AppStore.addLog("❌ 记账失败: ${e.message}")
                return@launch
            }

            when (result) {
                is LedgerRepository.RecordResult.Unparsed -> {
                    AppStore.addLog("⚠️ 记账跳过：${result.reason}")
                }
                is LedgerRepository.RecordResult.Duplicate -> {
                    AppStore.addLog("♻️ 重复通知已忽略（#${result.existing.id}）")
                }
                is LedgerRepository.RecordResult.Merged -> {
                    AppStore.addLog("🔗 与 #${result.into.id} 合并为同一笔")
                }
                is LedgerRepository.RecordResult.Created -> {
                    val txn = result.txn
                    AppStore.addLog("📒 已记账 #${result.id} ${txn.amountYuan} 元（待确认）")
                    // 用解析出的规范金额推送，而不是正则抓到的原始串，
                    // 确保与账本记录一致。
                    val priceStr = TxnParser.toYuan(txn.amountCents)
                    KtorClient.pushPayment(source, priceStr, txnId = result.id) { pushResult ->
                        ledgerScope.launch {
                            when (pushResult) {
                                is PushResult.Success ->
                                    repo.markPushSuccess(result.id, pushResult.orderId)
                                is PushResult.Failure ->
                                    repo.markPushFailed(result.id, pushResult.reason)
                                PushResult.NotConfigured -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}
