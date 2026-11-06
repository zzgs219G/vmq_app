package com.xixin.vmq.crash

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用日志落盘。
 *
 * 内存里的日志（AppStore._logs）重启即丢，出问题时没有现场。
 * 这里把同一条日志同步追加到文件，供事后排查。
 *
 * ## 滚动策略
 *
 * 单个文件超过 [MAX_FILE_BYTES] 就滚动成 app.log.1，最多保留 [KEEP_FILES] 个。
 * 不这么做的话，长期运行会占满用户存储。
 *
 * ## 为什么写文件要加锁
 *
 * 日志可能从多个线程（监听服务、IO 协程、主线程）写入，
 * 并发写同一文件会互相覆盖。
 */
object FileLogger {

    private const val MAX_FILE_BYTES = 512 * 1024L   // 单文件 512KB
    private const val KEEP_FILES = 3                  // 保留 app.log + 2 个历史

    private val lock = Any()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())

    @Volatile
    private var dir: File? = null

    /**
     * 初始化。应在 Application/Activity 最早时机调用。
     *
     * 外部存储不可用时回退内部，两者都不可用则关闭文件日志（内存日志仍然工作）。
     */
    fun init(context: Context) {
        if (dir != null) return
        val app = context.applicationContext
        val candidates = LogPaths.logCandidateDirs(app)
        dir = candidates.firstOrNull { LogPaths.isWritable(it) }
            ?: candidates.firstOrNull { runCatching { it.mkdirs() }.getOrDefault(false) }
    }

    fun log(msg: String) {
        val d = dir ?: return
        val line = "${fmt.format(Date())}  $msg\n"
        synchronized(lock) {
            runCatching {
                val f = File(d, LogPaths.FILE_APP_LOG)
                if (f.exists() && f.length() > MAX_FILE_BYTES) roll(d, f)
                f.appendText(line)
            }
        }
    }

    /** 滚动：app.log → app.log.1 → app.log.2，最旧的删除 */
    private fun roll(d: File, current: File) {
        runCatching {
            for (i in KEEP_FILES - 1 downTo 1) {
                val src = if (i == 1) current else File(d, "${LogPaths.FILE_APP_LOG}.${i - 1}")
                val dst = File(d, "${LogPaths.FILE_APP_LOG}.$i")
                if (src.exists()) {
                    if (dst.exists()) dst.delete()
                    src.renameTo(dst)
                }
            }
        }
    }

    /** 读取全部历史日志（外部+内部合并，按修改时间排序），供导出/上报 */
    fun readAll(context: Context): String {
        val app = context.applicationContext
        val files = LogPaths.logCandidateDirs(app)
            .flatMap { d -> d.listFiles()?.toList() ?: emptyList() }
            .filter { it.isFile && it.name.startsWith(LogPaths.FILE_APP_LOG) }
            .sortedBy { it.lastModified() }

        if (files.isEmpty()) return ""
        return buildString {
            files.forEach { f ->
                append("===== ${f.name} =====\n")
                runCatching { append(f.readText()) }
                append("\n")
            }
        }
    }

    fun totalSize(context: Context): Long =
        LogPaths.logCandidateDirs(context.applicationContext)
            .flatMap { d -> d.listFiles()?.toList() ?: emptyList() }
            .sumOf { it.length() }

    fun clear(context: Context) {
        LogPaths.logCandidateDirs(context.applicationContext).forEach { d ->
            d.listFiles()?.forEach { runCatching { it.delete() } }
        }
    }
}
