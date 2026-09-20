package io.github.adkimsm.neteasedownloader.ui

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 展示层格式化工具。纯函数,便于单测。
 */

private const val KB = 1024.0
private const val MB = KB * 1024
private const val GB = MB * 1024

/**
 * 字节数转可读体积。
 *
 * 修正原实现 `bytes / MB`(整数除法)在小于 1MB 时显示 "0MB" 的问题 ——
 * 例如一首 320kbps 的短歌约 500KB,旧实现会显示 0MB。
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "0 B"
    return when {
        bytes < KB -> "$bytes B"
        bytes < MB -> trimZero(bytes / KB) + " KB"
        bytes < GB -> trimZero(bytes / MB) + " MB"
        else -> trimZero(bytes / GB) + " GB"
    }
}

/** 保留一位小数,整数结果不带 ".0" */
private fun trimZero(value: Double): String {
    val rounded = (value * 10).roundToLong() / 10.0
    return if (rounded % 1.0 == 0.0) {
        rounded.toLong().toString()
    } else {
        String.format(Locale.US, "%.1f", rounded)
    }
}

/**
 * 毫秒时长转 mm:ss(歌曲时长)。
 */
fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0) return "--:--"
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}

/**
 * 剩余秒数转人话 ETA。
 *
 * 超过 1 小时用一位小数小时(大歌单首轮同步常见 2~4 小时),
 * 否则用分钟;不足 1 分钟显示"不到 1 分钟"。
 */
fun formatEta(seconds: Long): String = when {
    seconds <= 0 -> "即将完成"
    seconds < 60 -> "不到 1 分钟"
    seconds < 3600 -> "剩余约 ${seconds / 60} 分钟"
    else -> {
        val hours = seconds / 3600.0
        val rounded = (hours * 10).roundToLong() / 10.0
        val text = if (rounded % 1.0 == 0.0) {
            rounded.toLong().toString()
        } else {
            String.format(Locale.US, "%.1f", rounded)
        }
        "剩余约 $text 小时"
    }
}

/**
 * 百分比 0..100,分母为 0 时返回 null(UI 据此隐藏百分比)。
 */
fun progressPercent(done: Int, total: Int): Int? {
    if (total <= 0) return null
    if (done <= 0) return 0
    if (done >= total) return 100
    return (done * 100.0 / total).roundToLong().toInt()
}

/** 进度条 0f..1f 比例,分母为 0 时返回 null(UI 据此切成不确定态) */
fun progressFraction(done: Int, total: Int): Float? {
    if (total <= 0) return null
    return (done.toFloat() / total).coerceIn(0f, 1f)
}

/** 体积差值描述,例如 "需 1.2 GB / 剩 800 MB" */
fun formatStoragePair(needed: Long, available: Long): String =
    "${formatBytes(needed)} / 剩 ${formatBytes(available)}"

/** 判断是否空间不足(留 5% 余量,与 SyncEngine.SPACE_MARGIN 对齐) */
fun isStorageShort(needed: Long, available: Long): Boolean =
    needed > 0 && available > 0 && needed > (available * 0.95).toLong()

/** 供日志/调试用的绝对值格式化(此处保留 abs 语义,避免负数误展示) */
fun formatBytesAbs(bytes: Long): String = formatBytes(abs(bytes))
