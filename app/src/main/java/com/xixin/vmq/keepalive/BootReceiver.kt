package com.xixin.vmq.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * 开机自启。系统重启会销毁进程，通知监听授权保留但绑定不会自动恢复。
 * 详见 docs/后台保活.md
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val app = context.applicationContext

        if (!Watchdog.shouldRun(app)) return
        if (!ListenerStatus.permissionGranted(app)) return

        runCatching {
            NotificationListenerService.requestRebind(ListenerStatus.componentName(app))
        }.onFailure { Log.w(TAG, "requestRebind on boot failed", it) }

        // 开机瞬间系统仍处受限状态，延迟上门钟比立刻安排更可靠
        Watchdog.scheduleSoon(app, delayMs = 15_000L)
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
