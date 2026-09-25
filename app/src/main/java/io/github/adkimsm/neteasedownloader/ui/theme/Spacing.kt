package io.github.adkimsm.neteasedownloader.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 间距阶梯。所有页面内边距/元素间隙取这里的值,不再散落魔法数。
 */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
}

/**
 * 尺寸令牌。
 */
object Dimens {
    /** 全局页面内边距 */
    val ScreenPadding = 12.dp

    /** 最小可点区域(无障碍下限) */
    val TouchTarget = 48.dp

    /** 主操作按钮高度 */
    val PrimaryButton = 52.dp

    val IconSize = 20.dp

    /** 歌单封面缩略图 */
    val ThumbSize = 36.dp

    // ---- 骨架屏 ----
    val SkeletonLine = 14.dp
    val SkeletonThumb = 36.dp

    /** 骨架屏显示延迟:快于此时长的操作不显示 loading,避免闪烁 */
    const val SkeletonDelayMs = 200L

    /** 进度条高度 */
    val ProgressBar = 8.dp

    /** 卡片内部内边距 */
    val CardPadding = 14.dp

    /**
     * 诊断页等宽字号。
     * 这是全应用唯一允许出现显式字号的地方 —— 诊断日志需要在一屏内塞下尽可能多的行,
     * 不遵循正文阶梯。集中在此声明以免散落魔法数。
     */
    val DiagnosticsMono = 10.sp
    val DiagnosticsMonoLineHeight = 13.sp

    /** mini 播放条底部的进度线高度(纯装饰,不随窗口档位缩放) */
    val MiniProgressLine = 2.dp
}
