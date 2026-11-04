package com.xixin.vmq.keepalive

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/**
 * 监听服务的运行时状态。
 *
 * 区分「授权过」与「当前已绑定」：进程被冻结后授权仍在（设置里仍显示已授权），
 * 但 onNotificationPosted 不再回调。只看权限会误认为一切正常。
 */
object ListenerStatus {

    private const val TAG = "ListenerStatus"
    private const val PREFS = "vmq_listener"
    private const val KEY_LAST_EVENT_AT = "last_event_at"
    private const val KEY_LAST_CONNECTED_AT = "last_connected_at"

    @Volatile
    var connected: Boolean = false
        private set

    @Volatile
    var lastEventAt: Long = 0L
        private set

    fun markConnected(context: Context) {
        connected = true
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_CONNECTED_AT, System.currentTimeMillis()).apply()
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

    /** 是否已在系统设置里授予通知监听权限。注意 true 不代表服务当前已绑定 */
    fun permissionGranted(context: Context): Boolean =
        runCatching {
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
        }.getOrDefault(false)

    fun componentName(context: Context): ComponentName =
        ComponentName(context, com.xixin.vmq.service.PaymentNotificationListener::class.java)
}
