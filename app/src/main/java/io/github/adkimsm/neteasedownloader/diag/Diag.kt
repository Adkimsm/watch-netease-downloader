package io.github.adkimsm.neteasedownloader.diag

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 诊断日志:同时写 Logcat、滚动文件与内存环形缓冲。
 * 文件路径:getExternalFilesDir("logs")/diag.log(adb pull 可导出),超限轮转到 diag.log.1。
 * UI 通过 [logs] StateFlow 展示最近 [MAX_MEMORY_LINES] 行。
 */
object Diag {
    private const val TAG = "Diag"
    private const val MAX_FILE_BYTES = 256 * 1024
    private const val MAX_MEMORY_LINES = 1000

    @Volatile private var logDir: File? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val timestampFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val tail = ArrayDeque<String>(MAX_MEMORY_LINES + 64)

    /** App 启动时调用一次 */
    @Synchronized
    fun initialize(context: Context) {
        if (logDir != null) return
        val dir = runCatching { context.getExternalFilesDir(null) }.getOrNull()
        logDir = File(dir, "logs").also {
            it.mkdirs()
            it.setWritable(true)
        }
        i(TAG, "Diag 初始化完成,文件目录=${logDir?.absolutePath}")
        i(TAG, "设备:${Build.MODEL}, SDK=${Build.VERSION.SDK_INT}, 版本=${Build.VERSION.RELEASE}")
        i(TAG, "应用:${context.packageName}")
    }

    fun logFilePath(): String? =
        logDir?.let { File(it, "diag.log") }?.takeIf { it.exists() }?.absolutePath

    fun i(tag: String, msg: String) = write('I', tag, msg, null)
    fun w(tag: String, msg: String) = write('W', tag, msg, null)
    fun e(tag: String, msg: String) = write('E', tag, msg, null)
    fun e(tag: String, msg: String, t: Throwable) = write('E', tag, msg, t)
    fun d(tag: String, msg: String) = write('D', tag, msg, null)

    private fun write(level: Char, tagName: String, msg: String, t: Throwable?) {
        val line = buildString {
            append(timestampFormat.format(Date()))
            append(" $level/").append(tagName).append(": ").append(msg)
            if (t != null) {
                append("\n")
                val sw = java.io.StringWriter()
                t.printStackTrace(java.io.PrintWriter(sw))
                append(sw.toString().trim())
            }
        }
        @Suppress("DEPRECATION")
        when (level) {
            'D' -> Log.d(tagName, line)
            'W' -> Log.w(tagName, line)
            'E' -> Log.e(tagName, line)
            else -> Log.i(tagName, line)
        }
        appendToMemory(line)
        appendToFileAsync(line)
    }

    @Synchronized
    private fun appendToMemory(line: String) {
        tail.addLast(line)
        while (tail.size > MAX_MEMORY_LINES) tail.removeFirst()
        _logs.value = tail.toList()
    }

    private fun appendToFileAsync(line: String) {
        executor.execute {
            try {
                val dir = logDir ?: return@execute
                val file = File(dir, "diag.log")
                if (file.length() > MAX_FILE_BYTES) {
                    file.copyTo(File(dir, "diag.log.1"), overwrite = true)
                    file.delete()
                }
                FileWriter(file, true).use { it.append(line).append('\n') }
            } catch (_: Throwable) {
                // 日志失败不打扰主流程
            }
        }
    }

    /** 清空内存环形缓冲 */
    @Synchronized
    fun clearMemory() {
        tail.clear()
        _logs.value = emptyList()
    }

    /** Logcat 里也留一份汇总 */
    fun dumpToLogcat() {
        _logs.value.forEach { Log.i(TAG, it) }
    }
}