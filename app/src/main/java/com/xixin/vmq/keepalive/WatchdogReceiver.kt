package com.xixin.vmq.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * 看门狗唤醒接收器。
 *
 * ## 与「启动前台服务」方案的差异（重要）
 *
 * 常规保活套路是「闹钟唤醒 → startForegroundService」。但本应用监控的是
 * `NotificationListenerService`，**它不是普通 Service**：由系统在用户授权后绑定，
 * 应用侧无法通过 startService/startForegroundService 主动拉起来
 * （那样只会得到 "Unable to start service ... not allowed" 或启动一个没被系统绑定的空壳）。
 *
 * 正确手段是 `NotificationListenerService.requestRebind(componentName)`：
 * 它请求系统重新绑定这个监听服务，即使之前的绑定已被系统解除。
 * 这是通知监听类应用唯一合法的「自愈」入口。
 *
 * ## 唤醒后做什么
 *
 * 1. 若用户已关闭（should_run=false），直接返回，不自作主张重启
 * 2. 请求系统重新绑定监听服务
 * 3. 重新安排下一次看门狗（把闹钟链续上）
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Watchdog.ACTION_WATCHDOG) return

        val app = context.applicationContext
        Log.i(TAG, "watchdog fired")

        // 用户主动停止过就不再拉起，否则会出现「关了又自己开」。
        if (!Watchdog.shouldRun(app)) {
            Log.i(TAG, "watchdog fired but user stopped the listener, skip rebind")
            Watchdog.cancel(app)
            return
        }

        if (ListenerStatus.permissionGranted(app)) {
            runCatching {
                NotificationListenerService.requestRebind(ListenerStatus.componentName(app))
                Log.i(TAG, "requestRebind issued")
            }.onFailure { Log.w(TAG, "requestRebind failed", it) }
        } else {
            Log.w(TAG, "watchdog fired but listener permission not granted")
        }

        // 每次唤醒都续期，防止闹钟一次性触发后再无兜底。
        Watchdog.schedule(app)
    }

    private companion object {
        const val TAG = "WatchdogReceiver"
    }
}
