package io.github.adkimsm.neteasedownloader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.adkimsm.neteasedownloader.R
import io.github.adkimsm.neteasedownloader.data.PlaylistEntity
import io.github.adkimsm.neteasedownloader.ui.theme.BrandRed
import io.github.adkimsm.neteasedownloader.ui.theme.Dimens
import io.github.adkimsm.neteasedownloader.ui.theme.Spacing
import io.github.adkimsm.neteasedownloader.ui.theme.StateError
import io.github.adkimsm.neteasedownloader.ui.theme.SurfaceLevel2
import io.github.adkimsm.neteasedownloader.ui.theme.TextDisabled
import io.github.adkimsm.neteasedownloader.ui.theme.TextPrimary
import io.github.adkimsm.neteasedownloader.ui.theme.TextSecondary

/**
 * 跨屏复用组件。抽出这些是为了消除原先每屏各写一遍页面内边距与加载转圈的重复与不一致。
 */

/**
 * 统一页头。标题 + 可选返回 + 可选右侧动作。
 * 返回键保证 [Dimens.TouchTarget] 可点区域。
 */
@Composable
fun ScreenScaffold(
    modifier: Modifier = Modifier,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Dimens.ScreenPadding),
    ) {
        if (title != null || onBack != null || action != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimens.TouchTarget),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(Dimens.TouchTarget),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = TextPrimary,
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
                        modifier = Modifier.heightIn(min = Dimens.TouchTarget),
                        contentAlignment = Alignment.Center,
                    ) { action() }
                }
            }
        }
        content()
    }
}

/**
 * 主操作按钮。[loading] 为 true 时按钮内联转圈并自动禁用 ——
 * 全应用的主操作反馈统一走这里,不再各屏手写小转圈。
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.PrimaryButton),
        enabled = enabled && !loading,
        colors = ButtonDefaults.buttonColors(
            containerColor = BrandRed,
            contentColor = TextPrimary,
            disabledContainerColor = SurfaceLevel2,
            disabledContentColor = TextDisabled,
        ),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(Dimens.IconSize),
                strokeWidth = 2.dp,
                color = TextPrimary,
            )
        } else {
            Text(text = text, style = MaterialTheme.typography.bodyLarge)
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
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.TouchTarget),
        enabled = enabled && !loading,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(Dimens.IconSize),
                strokeWidth = 2.dp,
                color = if (danger) StateError else TextPrimary,
            )
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (danger) StateError else TextPrimary,
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
        containerColor = SurfaceLevel2,
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
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
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

/** 可内联错误条:承载 syncEngine 的失败原因(原实现里 progress.message 无处显示) */
@Composable
fun ErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(StateError.copy(alpha = 0.14f))
            .padding(Spacing.sm),
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
                modifier = Modifier.size(Dimens.TouchTarget),
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
 */
@Composable
fun PlaylistRow(
    playlist: PlaylistEntity,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    toggleLoading: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.TouchTarget)
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = !toggleLoading, role = Role.Checkbox) {
                onCheckedChange(!checked)
            }
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(Dimens.TouchTarget * 0.75f),
            contentAlignment = Alignment.Center,
        ) {
            if (toggleLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = BrandRed,
                )
            } else {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { onCheckedChange(it) },
                    enabled = !toggleLoading,
                )
            }
        }

        if (playlist.cover != null) {
            AsyncImage(
                model = playlist.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(Dimens.ThumbSize)
                    .clip(CircleShape),
            )
        } else {
            SkeletonThumb(size = Dimens.ThumbSize)
        }

        Spacer(Modifier.width(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.playlist_track_count, playlist.trackCount),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
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
fun CheckMark(color: Color, size: Dp = Dimens.IconSize) {
    Icon(
        imageVector = Icons.Filled.Check,
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(size),
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
