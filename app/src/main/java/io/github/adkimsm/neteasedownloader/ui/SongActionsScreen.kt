package io.github.adkimsm.neteasedownloader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.github.adkimsm.neteasedownloader.R
import io.github.adkimsm.neteasedownloader.player.Repeat
import io.github.adkimsm.neteasedownloader.ui.components.HDivider
import io.github.adkimsm.neteasedownloader.ui.components.ScreenScaffold
import io.github.adkimsm.neteasedownloader.ui.theme.Dimens
import io.github.adkimsm.neteasedownloader.ui.theme.GlassHighlight
import io.github.adkimsm.neteasedownloader.ui.theme.LocalWindowSizing
import io.github.adkimsm.neteasedownloader.ui.theme.AppShapes
import io.github.adkimsm.neteasedownloader.ui.theme.Spacing
import io.github.adkimsm.neteasedownloader.ui.theme.StateError
import io.github.adkimsm.neteasedownloader.ui.theme.SurfaceLevel2
import io.github.adkimsm.neteasedownloader.ui.theme.TextDisabled
import io.github.adkimsm.neteasedownloader.ui.theme.TextPrimary
import io.github.adkimsm.neteasedownloader.ui.theme.TextSecondary

/**
 * 单曲二级菜单。播放页的"更多"与曲目行的"⋮"进入的是**同一个页面**。
 *
 * 手表上不用 DropdownMenu:锚点菜单在 200dp 级别的窗口里会溢出屏幕,
 * 点击目标也远小于 36dp 的下限。整屏可滚动列表是这里唯一稳的形态。
 *
 * 删除放在最上面且远离播放设置:手表上误触一个播放模式无所谓,误触"删除"就是真的删了。
 * 先把这层壳与"队列 + 播放模式"落地,避免出现点了没反应的行。
 */
@Composable
fun SongActionsScreen(
    songTitle: String,
    hasPlayer: Boolean,
    currentRepeat: Repeat,
    shuffle: Boolean,
    onBack: () -> Unit,
    liked: Boolean,
    onToggleLike: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDelete: () -> Unit,
    onOpenQueue: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
) {
    val sizing = LocalWindowSizing.current

    ScreenScaffold(title = stringResource(R.string.actions_title), onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = songTitle,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.size(sizing.gapSm))
            HDivider()
            Spacer(Modifier.size(sizing.gapSm))
            // 破坏性操作置顶,且与下面的播放设置隔一条分隔线:
            // 手表上误触一个播放模式无所谓,误触"删除"就是真的删了
            ActionRow(
                icon = Icons.Filled.DeleteOutline,
                label = stringResource(R.string.actions_delete),
                trailing = null,
                onClick = onDelete,
                destructive = true,
            )
            Spacer(Modifier.size(sizing.gapSm))
            HDivider()
            Spacer(Modifier.size(sizing.gapSm))

            ActionRow(
                icon = if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                label = stringResource(
                    if (liked) R.string.actions_unlike else R.string.actions_like,
                ),
                trailing = null,
                onClick = onToggleLike,
            )
            Spacer(Modifier.size(sizing.gapSm / 2))
            ActionRow(
                icon = Icons.Filled.PlaylistAdd,
                label = stringResource(R.string.actions_add_to_playlist),
                trailing = null,
                onClick = onAddToPlaylist,
                chevron = true,
            )

            Spacer(Modifier.size(sizing.gapSm))
            HDivider()
            Spacer(Modifier.size(sizing.gapSm))

            if (hasPlayer) {
                ActionRow(
                    icon = Icons.Filled.PlaylistPlay,
                    label = stringResource(R.string.actions_queue),
                    trailing = null,
                    onClick = onOpenQueue,
                    chevron = true,
                )
                Spacer(Modifier.size(sizing.gapSm / 2))
                ActionRow(
                    icon = Icons.Filled.Repeat,
                    label = stringResource(R.string.actions_repeat),
                    trailing = repeatLabel(currentRepeat),
                    onClick = onCycleRepeat,
                )
                Spacer(Modifier.size(sizing.gapSm / 2))
                ActionRow(
                    icon = Icons.Filled.Repeat,
                    label = stringResource(R.string.actions_shuffle),
                    trailing = stringResource(
                        if (shuffle) R.string.common_on else R.string.common_off,
                    ),
                    onClick = onToggleShuffle,
                )
            } else {
                Text(
                    text = stringResource(R.string.actions_nothing_playing),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextDisabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun repeatLabel(repeat: Repeat): String = stringResource(
    when (repeat) {
        Repeat.OFF -> R.string.repeat_off
        Repeat.ALL -> R.string.repeat_all
        Repeat.ONE -> R.string.repeat_one
    },
)

/** 菜单行:图标 + 文案 + 右侧当前值/导航箭头。行高取档位令牌,保证 Compact 档也够点。 */
@Composable
internal fun ActionRow(
    icon: ImageVector,
    label: String,
    trailing: String?,
    onClick: () -> Unit,
    destructive: Boolean = false,
    subtitle: String? = null,
    chevron: Boolean = false,
) {
    val sizing = LocalWindowSizing.current
    val contentColor: Color = if (destructive) StateError else TextPrimary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = sizing.menuRowHeight)
            .clip(AppShapes.Row)
            .background(SurfaceLevel2)
            .border(Dimens.GlassBorder, GlassHighlight, AppShapes.Row)
            .clickable(onClick = onClick)
            .padding(horizontal = sizing.gapSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(sizing.iconSize),
        )
        Spacer(Modifier.width(sizing.gapSm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text = trailing,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
            )
        } else if (chevron) {
            Spacer(Modifier.width(Spacing.xs))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = TextDisabled,
                modifier = Modifier.size(sizing.iconSize),
            )
        }
    }
}
