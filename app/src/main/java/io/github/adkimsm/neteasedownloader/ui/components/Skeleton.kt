package io.github.adkimsm.neteasedownloader.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.adkimsm.neteasedownloader.ui.theme.Dimens
import io.github.adkimsm.neteasedownloader.ui.theme.Spacing
import io.github.adkimsm.neteasedownloader.ui.theme.SurfaceLevel3
import io.github.adkimsm.neteasedownloader.ui.theme.TextSecondary
import kotlinx.coroutines.delay

/**
 * 统一的 loading 骨架屏。
 *
 * 设计约定:全应用的等待反馈都走这里,不再各屏手写转圈。
 * 本地 SQLite 读取通常 <20ms,立即显示骨架屏会"闪一下"造成噪声,
 * 故所有骨架屏默认经 [rememberDelayedVisibility] 延迟 [Dimens.SkeletonDelayMs] 才出现:
 * 快操作完全无闪烁,慢操作有明确反馈。
 */

/**
 * 延迟可见性:true 后等 [delayMs] 才返回 true;若在延迟内变回 false 则始终 false。
 * 用于避免瞬时操作闪烁。
 */
@Composable
fun rememberDelayedVisibility(visible: Boolean, delayMs: Long = Dimens.SkeletonDelayMs): State<Boolean> {
    val shown = remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            delay(delayMs)
            shown.value = true
        } else {
            shown.value = false
        }
    }
    return shown
}

/**
 * 统一的呼吸式透明度动画。
 *
 * 刻意在页面级共享一个 infiniteTransition,而不是每块骨架独立动画:
 * 手表 CPU 较弱,一屏 5 行骨架各自跑动画会掉帧。
 */
@Composable
private fun shimmerAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonAlpha",
    )
    return alpha
}

/** 基础骨架块 */
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    width: Dp? = null,
    height: Dp = Dimens.SkeletonLine,
    shape: RoundedCornerShape = RoundedCornerShape(4.dp),
) {
    val alpha = shimmerAlpha()
    Box(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier)
            .height(height)
            .clip(shape)
            .alpha(alpha)
            .background(SurfaceLevel3),
    )
}

/** 单行文字占位,[widthFraction] 为占父宽比例 */
@Composable
fun SkeletonLine(
    modifier: Modifier = Modifier,
    widthFraction: Float = 1f,
    height: Dp = Dimens.SkeletonLine,
) {
    val alpha = shimmerAlpha()
    Box(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(4.dp))
            .alpha(alpha)
            .background(SurfaceLevel3),
    )
}

/**
 * 歌单列表骨架屏。形状与真实 [io.github.adkimsm.neteasedownloader.ui.components.PlaylistRow] 对齐
 * (行首勾选位 + 圆形封面 + 两行文字),避免加载完成时的布局跳动。
 */
@Composable
fun PlaylistSkeletonList(
    modifier: Modifier = Modifier,
    count: Int = 5,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        repeat(count) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 勾选位
                Box(modifier = Modifier.size(Dimens.TouchTarget * 0.6f))
                // 圆形封面
                SkeletonBox(
                    width = Dimens.SkeletonThumb,
                    height = Dimens.SkeletonThumb,
                    shape = RoundedCornerShape(50),
                )
                Spacer(Modifier.width(Spacing.sm))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    SkeletonLine(widthFraction = 0.7f)
                    SkeletonLine(widthFraction = 0.35f, height = 10.dp)
                }
            }
        }
    }
}

/**
 * 曲目列表骨架屏。形状与真实曲目行对齐(两行文字 + 右侧状态位),无封面 ——
 * 播放器不显示专辑图,骨架也不该凭空画一个方块出来。
 */
@Composable
fun TrackSkeletonList(
    modifier: Modifier = Modifier,
    count: Int = 6,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        repeat(count) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    SkeletonLine(widthFraction = 0.62f)
                    SkeletonLine(widthFraction = 0.3f, height = 10.dp)
                }
                Spacer(Modifier.width(Spacing.sm))
                SkeletonLine(widthFraction = 0.18f, height = 10.dp)
            }
        }
    }
}

/** 同步预览页的统计行骨架 */
@Composable
fun StatSkeleton(modifier: Modifier = Modifier, rows: Int = 3) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        repeat(rows) {
            SkeletonLine(widthFraction = 0.55f, height = 16.dp)
        }
    }
}

/**
 * 页面级加载态:骨架屏 + 可选文案。
 * 用于"整屏还没有内容可显示"的场景(而非局部刷新)。
 */
@Composable
fun LoadingOverlay(
    modifier: Modifier = Modifier,
    message: String? = null,
    content: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        content()
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
    }
}
/** 圆形封面骨架,供各屏复用 */
@Composable
fun SkeletonThumb(size: Dp = Dimens.SkeletonThumb) {
    SkeletonBox(width = size, height = size, shape = RoundedCornerShape(50))
}

