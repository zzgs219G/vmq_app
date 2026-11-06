package com.xixin.vmq.crash

import android.content.Context
import java.io.File

/**
 * 日志与崩溃文件的目录解析。
 *
 * 优先外部 `getExternalFilesDir(type)`，失败回退内部 `filesDir`。
 *
 * ⚠️ 为什么必须走 `getExternalFilesDir(type)` 而不是自己拼路径：
 * 外部 files 下的子目录只有经 framework 创建才会被登记为「App 可写」，
 * 自拼路径在分区存储下能列出空壳但写入直接 EACCES。
 *
 * ⚠️ 为什么不能只判 `canWrite()`：
 * 未登记的野目录 `canWrite()` 常返回 true，真正 `createNewFile()` 才失败。
 * 因此用「真写一个探针文件再删」来判定，见 [isWritable]。
 *
 * 读取端必须同时扫外部与内部两个位置 —— 进程早期（外部未挂载时）
 * 可能已把文件写进内部，只扫外部会「明明崩过却查不到」。
 */
object LogPaths {

    const val DIR_LOGS = "logs"
    const val DIR_CRASH = "crash_logs"

    const val FILE_APP_LOG = "app.log"

    private fun dir(context: Context, name: String): File {
        val base = context.getExternalFilesDir(name)
        return base ?: File(context.filesDir, name)
    }

    fun logsDir(context: Context): File = dir(context, DIR_LOGS)

    fun crashDir(context: Context): File = dir(context, DIR_CRASH)

    /** 候选目录列表（外部优先，内部兜底），读取端合并扫描用 */
    private fun candidates(context: Context, name: String): List<File> {
        val external = context.getExternalFilesDir(name)
        val internal = File(context.filesDir, name)
        return if (external != null && external.absolutePath != internal.absolutePath) {
            listOf(external, internal)
        } else {
            listOf(internal)
        }
    }

    fun crashCandidateDirs(context: Context): List<File> = candidates(context, DIR_CRASH)

    fun logCandidateDirs(context: Context): List<File> = candidates(context, DIR_LOGS)

    /** 真写一次来判定可写，不信 canWrite() */
    fun isWritable(dir: File): Boolean = runCatching {
        if (!dir.exists() && !dir.mkdirs()) return@runCatching false
        val probe = File(dir, ".write_probe")
        val created = probe.createNewFile()
        if (created) probe.delete()
        created
    }.getOrDefault(false)
}
