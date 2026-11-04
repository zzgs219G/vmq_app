package com.xixin.vmq.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * 看门狗唤醒接收器。
 *
 * 通知监听服务由系统绑定，应用侧无法 startService，只能用 requestRebind
 * 请求系统重新绑定。详见 docs/后台保活.md
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Watchdog.ACTION_WATCHDOG) return

        val app = context.applicationContext

        // 用户主动停止过就不拉起，否则会出现「关了又自己开」
        if (!Watchdog.shouldRun(app)) {
            Watchdog.cancel(app)
            return
        }

        if (ListenerStatus.permissionGranted(app)) {
            runCatching {
                NotificationListenerService.requestRebind(ListenerStatus.componentName(app))
            }.onFailure { Log.w(TAG, "requestRebind failed", it) }
        }

        // 每次唤醒都续期，防止闹钟一次性触发后再无兜底
        Watchdog.schedule(app)
    }

    private companion object {
        const val TAG = "WatchdogReceiver"
    }
}
