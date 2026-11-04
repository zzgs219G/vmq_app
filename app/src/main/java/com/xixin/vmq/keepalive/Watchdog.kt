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
 * ## 为什么必须要有它
 *
 * NotificationListenerService 由系统托管，本身有一定的存活能力，但**不代表不会被杀**：
 * - ColorOS/MIUI 的省电策略会在锁屏或长时间待机后直接冻结进程
 * - 用户从最近任务划掉应用时，部分 ROM 会连带销毁服务宿主进程
 * - 低内存时 LMK 会回收非前台进程
 *
 * 进程一旦被冻结，通知照样到达状态栏，但 `onNotificationPosted` 不再回调 ——
 * **表现得像正常工作，实际已经漏单**。这是本应用最危险的失效模式：无声无息。
 *
 * ## 为什么用 AlarmManager 而不是 Handler / ScheduledExecutor
 *
 * Doze 模式下应用没有 CPU 时间片，Handler 和 ScheduledExecutor **根本不会被调度**，
 * 靠它们做自愈等于没做。只有 `setExactAndAllowWhileIdle` 能在 Doze 下把进程叫醒。
 *
 * ## 间隔为什么是 9 分钟而不是更短
 *
 * 更频繁的唤醒会被 ColorOS 判定为异常唤醒并**拦截**，反而更不可靠。
 * 9 分钟是实践下来的平衡点。
 */
object Watchdog {

    private const val TAG = "Watchdog"

    /** 唤醒间隔。见类注释：太频繁会被 ROM 判为异常唤醒而拦截。 */
    private const val INTERVAL_MS = 9 * 60_000L

    private const val REQUEST_CODE = 8789

    const val ACTION_WATCHDOG = "com.xixin.vmq.WATCHDOG"

    private const val PREFS = "vmq_watchdog"
    private const val KEY_SHOULD_RUN = "should_run"
    private const val KEY_LAST_ARM = "last_arm_elapsed"

    /**
     * 用户意图：是否希望监听链路保持运行。
     *
     * 为什么需要这个标志：如果无条件自愈，用户主动点了「停止」之后，
     * 开机自启 / 看门狗 / onTaskRemoved 还会把它拉起来，用户会觉得「我都关了它怎么又自己开」。
     * 所有自动拉起路径都必须先过这道校验。
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
     * Android 12+ 即使用户授权过，也可能事后在设置里关掉「闹钟与提醒」，
     * 此时 setExactAndAllowWhileIdle 直接抛 SecurityException。
     * 因此先预检再决定用哪种，避免「精确失败 → 静默退化」，
     * 也便于把降级暴露在日志里（Doze 下非精确可能延迟数小时）。
     */
    fun schedule(context: Context) {
        val app = context.applicationContext
        runCatching {
            val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pendingIntent(app)
            val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS

            val canExact = Build.VERSION.SDK_INT < 31 || runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
            if (canExact) {
                runCatching {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                }.onFailure {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                }
            } else {
                Log.w(TAG, "exact alarm not permitted; watchdog falls back to inexact (may be delayed in Doze)")
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
            recordArm(app)
            Log.i(TAG, "watchdog scheduled in ${INTERVAL_MS / 60_000} min (exact=$canExact)")
        }.onFailure { Log.w(TAG, "watchdog schedule failed", it) }
    }

    fun cancel(context: Context) {
        runCatching {
            val am = context.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pendingIntent(context.applicationContext))
        }
        Log.i(TAG, "watchdog cancelled")
    }

    /**
     * 闹钟链自愈：Doze 下 AlarmManager 的闹钟可能被**直接丢弃**（而不是延后）。
     * 一旦丢弃，进程被杀后就再没有东西能把它拉起来 —— 这是「用一段时间后彻底失联」的根因。
     *
     * 每次维护 tick 核对一次上钟时刻，超过一个周期仍未重新上钟就补上。
     * 成本极低（一次 AlarmManager 调用），收益是把闹钟链从「单发」变成「自愈」。
     */
    fun rearmIfStale(context: Context) {
        val age = SystemClock.elapsedRealtime() - lastArm(context)
        if (lastArm(context) == 0L || age > INTERVAL_MS) {
            Log.i(TAG, "watchdog arm stale (age=${age / 60_000}min), re-arming")
            schedule(context)
        }
    }

    /** 供 UI 展示：上次上钟距今多少分钟（-1 表示从未上钟）。 */
    fun minutesSinceLastArm(context: Context): Long {
        val last = lastArm(context)
        if (last == 0L) return -1
        return (SystemClock.elapsedRealtime() - last) / 60_000
    }

    /**
     * 立刻安排一次短延迟唤醒（用于「刚被划掉、直接重启被系统拦」的兜底）。
     *
     * 闹钟唤醒属于系统回调，不受 Android 12+ 的后台启动前台服务限制，
     * 能拿到一次宝贵的前台服务启动配额。
     */
    fun scheduleSoon(context: Context, delayMs: Long = 5_000L) {
        val app = context.applicationContext
        runCatching {
            val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pendingIntent(app)
            val at = SystemClock.elapsedRealtime() + delayMs
            runCatching { am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi) }
                .onFailure { am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi) }
            recordArm(app)
            Log.i(TAG, "watchdog scheduled soon (+${delayMs}ms)")
        }.onFailure { Log.w(TAG, "scheduleSoon failed", it) }
    }
}
