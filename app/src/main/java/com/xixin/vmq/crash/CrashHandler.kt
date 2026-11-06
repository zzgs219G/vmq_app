package com.xixin.vmq.crash

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 未捕获异常捕获。
 *
 * ## 能抓到什么
 *
 * 只抓 Java/Kotlin 层的未捕获异常（空指针、状态异常等）。
 *
 * **抓不到**：ANR（主线程卡死被系统强杀）、Native 崩溃（进程级死亡）、
 * 主动 killProcess —— 这几种进程没有机会执行任何代码。
 * 本项目无 native 代码，ANR 风险也低（主线程不做重活），故不引入 xCrash。
 *
 * ## 落盘内容
 *
 * 崩溃堆栈 + 设备信息 + **崩溃前的应用日志快照**。
 * 最后这项最关键：光有堆栈只能知道崩在哪，有前序日志才知道崩之前发生了什么。
 */
object CrashHandler {

    private var appContext: Context? = null

    private val fileFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    private const val MAX_CRASH_FILES = 10

    /** 最近一次崩溃摘要，供 UI 在下次启动时提示 */
    @Volatile
    var lastCrashSummary: String? = null
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        FileLogger.init(context)

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { handle(thread, throwable) }
            // 交回系统默认处理，保留系统崩溃对话框与进程终止行为。
            // 不自己 killProcess：那会吞掉系统的上报与重启策略。
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun handle(thread: Thread, throwable: Throwable) {
        val ctx = appContext ?: return
        val info = buildInfo(thread, throwable)

        // 先落盘，再更新内存摘要
        saveToFile(ctx, info)
        lastCrashSummary = info.lineSequence().take(6).joinToString("\n")

        runCatching { FileLogger.log("💥 崩溃已记录: ${throwable.javaClass.simpleName}") }
    }

    private fun buildInfo(thread: Thread, throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val ctx = appContext

        return buildString {
            appendLine("========== 崩溃报告 ==========")
            appendLine("时间: $time")
            appendLine("线程: ${thread.name}")
            appendLine()
            appendLine("--- 设备 ---")
            appendLine("厂商型号: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("App: ${appVersion(ctx)}")
            appendLine()
            appendLine("--- 异常 ---")
            appendLine(sw.toString())

            // 崩溃前的应用日志：还原「崩之前发生了什么」
            if (ctx != null) {
                val logs = FileLogger.readAll(ctx)
                if (logs.isNotBlank()) {
                    appendLine("--- 崩溃前的应用日志（末尾 200 行）---")
                    appendLine(logs.lines().filter { it.isNotBlank() }.takeLast(200).joinToString("\n"))
                }
            }
            appendLine("========== 报告结束 ==========")
        }
    }

    private fun appVersion(ctx: Context?): String = runCatching {
        ctx ?: return "未知"
        val pm = ctx.packageManager
        val pi = pm.getPackageInfo(ctx.packageName, 0)
        "${pi.versionName} (${pi.longVersionCode})"
    }.getOrDefault("未知")

    private fun saveToFile(ctx: Context, content: String) {
        val dir = LogPaths.crashDir(ctx)
        if (!LogPaths.isWritable(dir)) return
        runCatching {
            val name = "crash_${fileFmt.format(Date())}.txt"
            File(dir, name).writeText(content)
            prune(dir)
            FileLogger.log("崩溃日志已保存: $name")
        }
    }

    /** 只保留最近 MAX_CRASH_FILES 个崩溃文件 */
    private fun prune(dir: File) {
        runCatching {
            dir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("crash_") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_CRASH_FILES)
                ?.forEach { it.delete() }
        }
    }

    /** 读取所有崩溃记录（外部+内部合并，新的在前） */
    fun readAll(ctx: Context): List<File> =
        LogPaths.crashCandidateDirs(ctx.applicationContext)
            .flatMap { d -> d.listFiles()?.toList() ?: emptyList() }
            .filter { it.isFile && it.name.startsWith("crash_") }
            .sortedByDescending { it.lastModified() }

    fun clearAll(ctx: Context) {
        LogPaths.crashCandidateDirs(ctx.applicationContext).forEach { d ->
            d.listFiles()?.forEach { runCatching { it.delete() } }
        }
        lastCrashSummary = null
    }
}
