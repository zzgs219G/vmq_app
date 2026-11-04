package com.xixin.vmq.keepalive

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/**
 * 监听服务的运行时状态。
 *
 * 为什么要单独抽出来：`NotificationManagerCompat.getEnabledListenerPackages` 只能说明
 * **用户授权过**，不能说明服务**当前已绑定**。两者差距很大 ——
 * 进程被冻结/回收后，权限还在（设置里仍显示已授权），但 `onNotificationPosted` 不再回调。
 * 界面上如果只看权限，会显示「运行中」，而实际上已经漏单。
 *
 * 因此绑定状态由服务自身在 onListenerConnected / onListenerDisconnected 里写入本对象，
 * UI 与看门狗都读这里。
 */
object ListenerStatus {

    private const val TAG = "ListenerStatus"
    private const val PREFS = "vmq_listener"
    private const val KEY_LAST_EVENT_AT = "last_event_at"
    private const val KEY_LAST_CONNECTED_AT = "last_connected_at"

    /** 服务当前是否已绑定。仅进程内有效，跨进程重启后由服务重新写入。 */
    @Volatile
    var connected: Boolean = false
        private set

    /** 最近一次收到通知的时刻（用于判断链路是否还活着）。 */
    @Volatile
    var lastEventAt: Long = 0L
        private set

    fun markConnected(context: Context) {
        connected = true
        val now = System.currentTimeMillis()
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_CONNECTED_AT, now)
            .apply()
        Log.i(TAG, "listener connected")
    }

    fun markDisconnected(context: Context) {
        connected = false
        Log.w(TAG, "listener disconnected")
    }

    fun markEvent(context: Context) {
        lastEventAt = System.currentTimeMillis()
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_EVENT_AT, lastEventAt).apply()
    }

    fun lastConnectedAt(context: Context): Long =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_CONNECTED_AT, 0L)

    fun lastEventAt(context: Context): Long =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_EVENT_AT, 0L)

    /**
     * 用户是否已在系统设置里授予通知监听权限。
     * 注意：true 只代表「授权过」，不代表服务当前已绑定。
     */
    fun permissionGranted(context: Context): Boolean =
        runCatching {
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
        }.getOrDefault(false)

    fun componentName(context: Context): ComponentName =
        ComponentName(context, com.xixin.vmq.service.PaymentNotificationListener::class.java)
}
