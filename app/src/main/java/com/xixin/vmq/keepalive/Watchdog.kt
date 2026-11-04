package com.xixin.vmq.keepalive

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * 看门狗：定时用 AlarmManager 检查监听服务是否还活着。
 *
 * 为什么用 AlarmManager 而不是 Handler：Doze 下应用没有 CPU 时间片，
 * Handler 与 ScheduledExecutor 根本不会被调度。只有精确闹钟能把进程叫醒。
 * 详细说明见 docs/后台保活.md
 */
object Watchdog {

    private const val TAG = "Watchdog"

    /** 唤醒间隔。更频繁会被 ColorOS 判为异常唤醒并拦截，反而更不可靠 */
    private const val INTERVAL_MS = 9 * 60_000L

    private const val REQUEST_CODE = 8789

    const val ACTION_WATCHDOG = "com.xixin.vmq.WATCHDOG"

    private const val PREFS = "vmq_watchdog"
    private const val KEY_SHOULD_RUN = "should_run"
    private const val KEY_LAST_ARM = "last_arm_elapsed"

    /**
     * 用户是否希望保持运行。所有自动拉起路径都先检查它，
     * 避免出现「用户点了停止，开机又自己拉起来」。
     */
    fun shouldRun(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOULD_RUN, true)

    fun setShouldRun(context: Context, value: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOULD_RUN, value).apply()
    }

    private fun recordArm(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_ARM, SystemClock.elapsedRealtime())
            .apply()
    }

    private fun lastArm(context: Context): Long =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_ARM, 0L)

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, WatchdogReceiver::class.java).setAction(ACTION_WATCHDOG),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /**
     * 安排下一次唤醒。
     *
     * Android 12+ 用户可能关掉「闹钟与提醒」，此时 setExactAndAllowWhileIdle
     * 直接抛 SecurityException，因此先预检再决定用哪种，并记日志暴露降级。
     */
    fun schedule(context: Context) {
        val app = context.applicationContext
        runCatching {
            val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pendingIntent(app)
            val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS

            val canExact = Build.VERSION.SDK_INT < 31 ||
                runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)

            if (canExact) {
                runCatching {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                }.onFailure {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                }
            } else {
                Log.w(TAG, "exact alarm not permitted; fallback to inexact (may delay in Doze)")
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
            recordArm(app)
        }.onFailure { Log.w(TAG, "watchdog schedule failed", it) }
    }

    fun cancel(context: Context) {
        runCatching {
            val am = context.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pendingIntent(context.applicationContext))
        }
    }

    /**
     * 闹钟链自愈：Doze 下闹钟可能被直接丢弃（不是延后），
     * 一旦丢弃，进程被杀后再没有东西能把它拉起来。
     */
    fun rearmIfStale(context: Context) {
        val age = SystemClock.elapsedRealtime() - lastArm(context)
        if (lastArm(context) == 0L || age > INTERVAL_MS) {
            schedule(context)
        }
    }

    /** 上次上钟距今的分钟数，-1 表示从未上钟 */
    fun minutesSinceLastArm(context: Context): Long {
        val last = lastArm(context)
        if (last == 0L) return -1
        return (SystemClock.elapsedRealtime() - last) / 60_000
    }

    /** 立刻安排一次短延迟唤醒。闹钟唤醒属系统回调，不受后台启动限制 */
    fun scheduleSoon(context: Context, delayMs: Long = 5_000L) {
        val app = context.applicationContext
        runCatching {
            val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pendingIntent(app)
            val at = SystemClock.elapsedRealtime() + delayMs
            runCatching { am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi) }
                .onFailure { am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi) }
            recordArm(app)
        }.onFailure { Log.w(TAG, "scheduleSoon failed", it) }
    }
}
