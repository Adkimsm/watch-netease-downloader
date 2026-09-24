package io.github.adkimsm.neteasedownloader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.adkimsm.neteasedownloader.R
import io.github.adkimsm.neteasedownloader.data.PlaylistEntity
import io.github.adkimsm.neteasedownloader.ui.theme.BrandRed
import io.github.adkimsm.neteasedownloader.ui.theme.BrandRedGradientEnd
import io.github.adkimsm.neteasedownloader.ui.theme.BrandRedGradientStart
import io.github.adkimsm.neteasedownloader.ui.theme.Dimens
import io.github.adkimsm.neteasedownloader.ui.theme.GlassHighlight
import io.github.adkimsm.neteasedownloader.ui.theme.LocalWindowSizing
import io.github.adkimsm.neteasedownloader.ui.theme.AppShapes
import io.github.adkimsm.neteasedownloader.ui.theme.Spacing
import io.github.adkimsm.neteasedownloader.ui.theme.StateError
import io.github.adkimsm.neteasedownloader.ui.theme.SurfaceLevel2
import io.github.adkimsm.neteasedownloader.ui.theme.SurfaceLevel3
import io.github.adkimsm.neteasedownloader.ui.theme.SurfaceLevel4
import io.github.adkimsm.neteasedownloader.ui.theme.TextDisabled
import io.github.adkimsm.neteasedownloader.ui.theme.TextPrimary
import io.github.adkimsm.neteasedownloader.ui.theme.TextSecondary
import io.github.adkimsm.neteasedownloader.ui.theme.WindowClass
import io.github.adkimsm.neteasedownloader.ui.theme.WindowSizing

/**
 * 跨屏复用组件。抽出这些是为了消除原先每屏各写一遍页面内边距与加载转圈的重复与不一致。
 *
 * 风格:高级深色 + 圆角毛玻璃 —— 卡片带 1dp 高光描边,按钮用品牌红渐变。
 */

/**
 * 统一页头。标题 + 可选返回 + 可选右侧动作。
 * 返回键保证 [WindowSizing.touchTarget] 可点区域。
 */
@Composable
fun ScreenScaffold(
    modifier: Modifier = Modifier,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    // 页头尺寸随窗口档次收缩,小屏上不再固定占用 48dp 高度
    val sizing = LocalWindowSizing.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(sizing.screenPadding),
    ) {
        if (title != null || onBack != null || action != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = sizing.headerMinHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(sizing.touchTarget),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = TextPrimary,
                            modifier = Modifier.size(sizing.iconSize),
                        )
                    }
                }
                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = if (onBack != null) 0.dp else Spacing.xs),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (action != null) {
                    Box(
                        modifier = Modifier.heightIn(min = sizing.touchTarget),
                        contentAlignment = Alignment.Center,
                    ) { action() }
                }
            }
        }
        content()
    }
}

/** 毛玻璃卡片底:圆角 + 1dp 高光描边 + 抬升底色 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    container: Color = SurfaceLevel2,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val base = modifier
        .fillMaxWidth()
        .clip(AppShapes.Card)
        .background(container)
        .border(Dimens.GlassBorder, GlassHighlight, AppShapes.Card)
    val clickable = if (onClick != null) base.clickable(onClick = onClick) else base
    Box(
        modifier = clickable.padding(Dimens.CardPadding),
        contentAlignment = Alignment.CenterStart,
    ) { content() }
}

/**
 * 主操作按钮。[loading] 为 true 时按钮内联转圈并自动禁用 ——
 * 全应用的主操作反馈统一走这里,不再各屏手写小转圈。
 * 使用品牌红渐变背景 + 大圆角,强化主操作。
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val sizing = LocalWindowSizing.current
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = sizing.primaryButtonHeight),
        enabled = enabled && !loading,
        shape = AppShapes.Control,
        colors = ButtonDefaults.buttonColors(
            containerColor = BrandRed,
            contentColor = TextPrimary,
            disabledContainerColor = SurfaceLevel3,
            disabledContentColor = TextDisabled,
        ),
        contentPadding = PaddingValues(horizontal = sizing.gapMd, vertical = 0.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(sizing.iconSize),
                strokeWidth = 2.dp,
                color = TextPrimary,
            )
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 渐变主操作按钮:背景用品牌红渐变(毛玻璃高光版) */
@Composable
fun GradientPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val sizing = LocalWindowSizing.current
    val brush = Brush.horizontalGradient(
        listOf(BrandRedGradientStart, BrandRedGradientEnd),
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = sizing.primaryButtonHeight)
            .clip(AppShapes.Control)
            .then(
                if (enabled && !loading) Modifier.background(brush)
                else Modifier.background(SurfaceLevel3),
            )
            .clickable(enabled = enabled && !loading, onClick = onClick)
            .padding(horizontal = sizing.gapMd),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(sizing.iconSize),
                strokeWidth = 2.dp,
                color = TextPrimary,
            )
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) TextPrimary else TextDisabled,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 次要操作按钮(返回 / 取消) */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    danger: Boolean = false,
) {
    val sizing = LocalWindowSizing.current
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = sizing.secondaryButtonHeight),
        enabled = enabled && !loading,
        shape = AppShapes.Control,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (danger) StateError else TextPrimary,
            disabledContentColor = TextDisabled,
        ),
        contentPadding = PaddingValues(horizontal = sizing.gapMd, vertical = 0.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(sizing.iconSize),
                strokeWidth = 2.dp,
                color = if (danger) StateError else TextPrimary,
            )
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (danger) StateError else TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 二次确认对话框。
 * 用于不可逆操作(停止同步、退出登录、删除文件),项目此前零处确认。
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmText,
                    color = if (destructive) StateError else BrandRed,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.common_cancel), color = TextSecondary)
            }
        },
        containerColor = SurfaceLevel4,
        shape = AppShapes.Control,
    )
}

/** 统计行:左标签 + 右数值 */
@Composable
fun StatRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = TextPrimary,
    emphasized: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = if (emphasized) {
                MaterialTheme.typography.bodyMedium
            } else {
                MaterialTheme.typography.bodySmall
            },
            color = TextSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = if (emphasized) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = valueColor,
        )
    }
}

/** 状态徽标:色 + 图标 + 文案三重编码 */
@Composable
fun StateBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val sizing = LocalWindowSizing.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = sizing.gapSm, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

/**
 * 分组标题:设置页等纵向分组的统一小标题。
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = TextSecondary,
        modifier = modifier.fillMaxWidth(),
    )
}

/** 可内联错误条:承载 syncEngine 的失败原因(原实现里 progress.message 无处显示) */
@Composable
fun ErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val sizing = LocalWindowSizing.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(StateError.copy(alpha = 0.14f))
            .padding(sizing.gapSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = StateError,
            )
            if (onRetry != null) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = stringResource(R.string.common_retry),
                    style = MaterialTheme.typography.labelSmall,
                    color = BrandRed,
                    modifier = Modifier.clickable(onClick = onRetry),
                )
            }
        }
        if (onDismiss != null) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(sizing.touchTarget),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.common_close),
                    tint = StateError,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * 歌单行:行首勾选位 + 圆形封面 + 名称/曲数。
 *
 * 整行可点切换勾选(而非只点 Checkbox),手表上更易命中。
 * [toggleLoading] 为该行写库中的加载态。
 * 毛玻璃卡片行:圆角 + 高光描边 + 抬升底色。
 */
@Composable
fun PlaylistRow(
    playlist: PlaylistEntity,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    toggleLoading: Boolean = false,
) {
    val sizing = LocalWindowSizing.current
    // 小屏上一行要同时放下勾选位、封面和两行文字:
    // 勾选标记改画在封面右下角的小圆点上,省掉独立的勾选槽位。
    val showStandaloneCheckbox = sizing.windowClass != WindowClass.Compact

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = sizing.touchTarget)
            .clip(AppShapes.Row)
            .background(SurfaceLevel2)
            .border(Dimens.GlassBorder, GlassHighlight, AppShapes.Row)
            // 点**行**进歌单详情;点**勾选框/角标**才切换是否纳入同步。
            // 播放器里"进歌单听歌"是主操作,不该和"是否下载"共用同一个手势。
            .clickable(enabled = !toggleLoading, onClick = onOpen)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showStandaloneCheckbox) {
            Box(
                modifier = Modifier.size(sizing.touchTarget * 0.75f),
                contentAlignment = Alignment.Center,
            ) {
                if (toggleLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(sizing.iconSize * 0.9f),
                        strokeWidth = 2.dp,
                        color = BrandRed,
                    )
                } else {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { onCheckedChange(it) },
                        enabled = !toggleLoading,
                        modifier = Modifier.size(sizing.iconSize),
                    )
                }
            }
        } else if (toggleLoading) {
            // 极窄窗口:角标位置改为小转圈,保留写入中的反馈
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(end = Spacing.xs)
                    .size(sizing.iconSize * 0.9f),
                strokeWidth = 2.dp,
                color = BrandRed,
            )
        }

        // 封面 + 勾选角标(仅极窄窗口);选中态同时用文字颜色区分,不单靠颜色或角标
        Box {
            if (playlist.cover != null) {
                AsyncImage(
                    model = playlist.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(sizing.thumbSize)
                        .clip(CircleShape),
                )
            } else {
                SkeletonThumb(size = sizing.thumbSize)
            }
            // 极窄档没有独立勾选槽位,角标本身就是勾选开关,自身可点
            if (!showStandaloneCheckbox) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(sizing.touchTarget * 0.6f)
                        .clip(CircleShape)
                        .background(if (checked) BrandRed else SurfaceLevel3)
                        .clickable(enabled = !toggleLoading) { onCheckedChange(!checked) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (checked) CheckMark(color = TextPrimary, size = sizing.iconSize * 0.5f)
                }
            }
        }

        Spacer(Modifier.width(sizing.gapSm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (checked) TextPrimary else TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.playlist_track_count, playlist.trackCount),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 水平分隔线 */
@Composable
fun HDivider(modifier: Modifier = Modifier, color: Color = io.github.adkimsm.neteasedownloader.ui.theme.Divider) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color),
    )
}

/** 选中勾:设置页/音质项共用 */
@Composable
fun CheckMark(color: Color, size: Dp? = null) {
    val sizing = LocalWindowSizing.current
    Icon(
        imageVector = Icons.Filled.Check,
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(size ?: sizing.iconSize),
    )
}

/** 常用纵向间距组合,避免各屏重复写 Spacer */
@Composable
fun VGap(size: Dp = Spacing.md) {
    Spacer(Modifier.height(size))
}

/** 常用横向间距组合 */
@Composable
fun HGap(size: Dp = Spacing.sm) {
    Spacer(Modifier.width(size))
}

/** 供各屏统一使用的一组顺序排布常量 */
object LayoutDefaults {
    val RowSpacing = Spacing.sm
    val ColumnSpacing = Spacing.md
    val ScreenPadding = Dimens.ScreenPadding
}
