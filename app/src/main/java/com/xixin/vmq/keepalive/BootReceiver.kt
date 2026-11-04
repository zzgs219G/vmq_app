package com.xixin.vmq.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * 开机自启。
 *
 * ## 为什么必须有它
 *
 * 系统重启（含 OTA、手动重启）会销毁全部进程。通知监听服务的授权**会保留**，
 * 但绑定不会自动恢复 —— 用户会发现「每次重启手机后就不上报了」，必须手动打开一次应用。
 *
 * ## 为什么用 requestRebind 而不是 startForegroundService
 *
 * NotificationListenerService 由系统绑定，应用无法主动 start。见 [WatchdogReceiver] 的说明。
 *
 * ## 为什么要检查 should_run
 *
 * 用户在应用内主动停止过（should_run=false）时，开机不该自作主张地恢复，
 * 避免出现「我都关了它怎么又自己开」。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        // HTC/部分 ROM 用 QUICKBOOT_POWERON，一并接受。
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val app = context.applicationContext
        Log.i(TAG, "boot/replace received: $action")

        if (!Watchdog.shouldRun(app)) {
            Log.i(TAG, "skip: user stopped the listener")
            return
        }
        if (!ListenerStatus.permissionGranted(app)) {
            Log.w(TAG, "skip: listener permission not granted")
            return
        }

        runCatching {
            NotificationListenerService.requestRebind(ListenerStatus.componentName(app))
            Log.i(TAG, "requestRebind issued on boot")
        }.onFailure { Log.w(TAG, "requestRebind on boot failed", it) }

        // 开机后重启闹钟链。开机瞬间系统可能仍处于受限状态，
        // 这里稍作延迟再上门钟，比立刻安排更可靠。
        Watchdog.scheduleSoon(app, delayMs = 15_000L)
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
