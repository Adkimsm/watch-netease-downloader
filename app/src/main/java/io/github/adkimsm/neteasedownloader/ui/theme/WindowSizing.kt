package io.github.adkimsm.neteasedownloader.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 手表小屏自适应尺寸。
 *
 * 背景:此前所有尺寸都写死在 [Dimens] 里(如 ScreenPadding = 12.dp / TouchTarget = 48.dp),
 * 在 372×430 这类手表窗口上,行内「勾选位 + 封面 + 间距」已吃掉约 80dp 宽度,
 * 留给歌单名的横向空间所剩无几;进度页纵向堆叠也容易把停止按钮顶出屏幕。
 *
 * 做法:按窗口短边(dp)划分档位,再由档位导出整套尺寸,页面只读 [LocalWindowSizing],
 * 不再直接取 [Dimens] 的固定值。这样 320px 的小表和 372px 的表都能得到合适的密度,
 * 而不是只对某一款机型调参。
 */

/**
 * 窗口尺寸档位。
 *
 * 用**短边**判断:竖屏取宽、横屏取高,保证同一块表换方向后仍落在同一档,
 * 避免旋转时按钮大小跳变。
 */
enum class WindowClass {
    /** 极窄窗口(<300dp),最小号手表 */
    Compact,

    /** 常规手表窗口(300~360dp),本项目主力机型 372×430 落在这里附近 */
    Medium,

    /** 大屏手表 / 方屏(>360dp) */
    Expanded,
}

/**
 * 由窗口短边导出的整套尺寸令牌。
 *
 * 逐项缩放而非整体乘系数:触控目标有下限(不得小于 36dp,低于此手表上很难点中),
 * 而纯装饰项(封面、图标)可以放心缩小。
 */
data class WindowSizing(
    val windowClass: WindowClass,
    /** 页面内边距 */
    val screenPadding: Dp,
    /** 可点区域下限。手表上按 40dp 起算,低于手机的 48dp 但仍在可接受范围 */
    val touchTarget: Dp,
    /** 主操作按钮高度 */
    val primaryButtonHeight: Dp,
    /** 次要按钮高度 */
    val secondaryButtonHeight: Dp,
    /** 歌单封面缩略图 */
    val thumbSize: Dp,
    /** 图标尺寸 */
    val iconSize: Dp,
    /** 进度条高度 */
    val progressBar: Dp,
    /** 骨架行高 */
    val skeletonLine: Dp,
    /** 页头最小高度 */
    val headerMinHeight: Dp,
    /** 进度页大号百分比字号(sp),整数以便拼进 TextStyle */
    val progressPercentSp: Int,
    /** 底部操作区可用的最大垂直占比,超过则内容区滚动 */
    val footerMaxHeightFraction: Float,
) {
    /** 水平/垂直标准间隙,随屏幕档次收缩 */
    val gapSm: Dp get() = if (windowClass == WindowClass.Compact) 6.dp else Spacing.sm
    val gapMd: Dp get() = if (windowClass == WindowClass.Compact) 8.dp else Spacing.md

    /** 供页面统一使用的内边距 */
    val screenPaddingValues: PaddingValues get() = PaddingValues(screenPadding)
}

/** 极窄窗口:320×320 一类的圆形/小方表 */
private val CompactSizing = WindowSizing(
    windowClass = WindowClass.Compact,
    screenPadding = 6.dp,
    touchTarget = 36.dp,
    primaryButtonHeight = 42.dp,
    secondaryButtonHeight = 36.dp,
    thumbSize = 28.dp,
    iconSize = 18.dp,
    progressBar = 6.dp,
    skeletonLine = 12.dp,
    headerMinHeight = 36.dp,
    progressPercentSp = 24,
    footerMaxHeightFraction = 0.55f,
)

/** 常规手表窗口:372×430 主力机型 */
private val MediumSizing = WindowSizing(
    windowClass = WindowClass.Medium,
    screenPadding = 10.dp,
    touchTarget = 42.dp,
    primaryButtonHeight = 48.dp,
    secondaryButtonHeight = 40.dp,
    thumbSize = 32.dp,
    iconSize = 20.dp,
    progressBar = 8.dp,
    skeletonLine = 14.dp,
    headerMinHeight = 42.dp,
    progressPercentSp = 26,
    footerMaxHeightFraction = 0.6f,
)

/** 大屏/方屏手表:沿用原先的宽松尺寸 */
private val ExpandedSizing = WindowSizing(
    windowClass = WindowClass.Expanded,
    screenPadding = Dimens.ScreenPadding,
    touchTarget = Dimens.TouchTarget,
    primaryButtonHeight = Dimens.PrimaryButton,
    secondaryButtonHeight = Dimens.TouchTarget,
    thumbSize = Dimens.ThumbSize,
    iconSize = Dimens.IconSize,
    progressBar = Dimens.ProgressBar,
    skeletonLine = Dimens.SkeletonLine,
    headerMinHeight = Dimens.TouchTarget,
    progressPercentSp = 28,
    footerMaxHeightFraction = 0.7f,
)

/**
 * 短边转档位。
 *
 * 阈值取 300dp / 360dp:
 *  - <300dp:即便按最小号手表算,页面内边距也只能给到 6dp
 *  - ≥360dp:放得下原始 Material 尺寸,不必压缩
 */
internal fun windowClassFor(shortestSideDp: Int): WindowClass = when {
    shortestSideDp < 300 -> WindowClass.Compact
    shortestSideDp < 360 -> WindowClass.Medium
    else -> WindowClass.Expanded
}

internal fun sizingFor(windowClass: WindowClass): WindowSizing = when (windowClass) {
    WindowClass.Compact -> CompactSizing
    WindowClass.Medium -> MediumSizing
    WindowClass.Expanded -> ExpandedSizing
}

/**
 * 当前窗口的尺寸令牌。
 *
 * 默认值为 [MediumSizing]:Compose 预览与单元测试里没有真实 Configuration,
 * 给一个主力机型的合理缺省,避免出现 0dp 的退化布局。
 */
val LocalWindowSizing = compositionLocalOf { MediumSizing }

/** 从 Configuration 推导当前窗口尺寸;屏幕旋转由 configChanges 处理,会触发重组 */
@Composable
@ReadOnlyComposable
fun rememberWindowSizing(): WindowSizing {
    val configuration = LocalConfiguration.current
    val shortestSideDp = minOf(
        configuration.screenWidthDp,
        configuration.screenHeightDp,
    )
    return sizingFor(windowClassFor(shortestSideDp))
}
