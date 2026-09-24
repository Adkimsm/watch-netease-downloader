package io.github.adkimsm.neteasedownloader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.github.adkimsm.neteasedownloader.R
import io.github.adkimsm.neteasedownloader.data.SongEntity
import io.github.adkimsm.neteasedownloader.ui.theme.BrandRed
import io.github.adkimsm.neteasedownloader.ui.theme.Dimens
import io.github.adkimsm.neteasedownloader.ui.theme.GlassHighlight
import io.github.adkimsm.neteasedownloader.ui.theme.LocalWindowSizing
import io.github.adkimsm.neteasedownloader.ui.theme.AppShapes
import io.github.adkimsm.neteasedownloader.ui.theme.SurfaceLevel2
import io.github.adkimsm.neteasedownloader.ui.theme.TextPrimary
import io.github.adkimsm.neteasedownloader.ui.theme.TextSecondary

/**
 * mini 播放条:歌单列表 / 曲目列表底部常驻。
 *
 * **无封面**(下载回来的文件没有专辑图),只有歌名 + 播放暂停 + 底部细进度线 ——
 * 手表上这条要尽量矮,把垂直空间留给列表。
 * [progressFraction] 非 null 时在条底画一条 2dp 品牌红进度线(播放位置反馈)。
 *
 * 毛玻璃卡片:圆角 + 1dp 高光描边,与列表行同语言。
 */
@Composable
fun MiniPlayerBar(
    song: SongEntity?,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onTogglePlayPause: () -> Unit,
    progressFraction: Float? = null,
    modifier: Modifier = Modifier,
) {
    val sizing = LocalWindowSizing.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(AppShapes.Row)
            .background(SurfaceLevel2)
            .border(Dimens.GlassBorder, GlassHighlight, AppShapes.Row)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = sizing.touchTarget + sizing.gapSm)
                .padding(start = sizing.gapSm, end = sizing.gapSm / 2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song?.name ?: stringResource(R.string.player_nothing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = song?.artist.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onTogglePlayPause, modifier = Modifier.size(sizing.touchTarget)) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(
                        if (isPlaying) R.string.player_pause else R.string.player_play,
                    ),
                    tint = BrandRed,
                    modifier = Modifier.size(sizing.iconSize),
                )
            }
        }
        if (progressFraction != null) {
            // 条底进度线:播放位置反馈,纯装饰不拦截点击
            Box(
                modifier = Modifier
                    .fillMaxWidth(progressFraction.coerceIn(0f, 1f))
                    .height(Dimens.MiniProgressLine)
                    .background(BrandRed),
            )
        }
    }
}
