package io.github.adkimsm.neteasedownloader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.adkimsm.neteasedownloader.R
import io.github.adkimsm.neteasedownloader.data.SongEntity
import io.github.adkimsm.neteasedownloader.ui.components.ScreenScaffold
import io.github.adkimsm.neteasedownloader.ui.theme.BrandRed
import io.github.adkimsm.neteasedownloader.ui.theme.BrandRedMuted
import io.github.adkimsm.neteasedownloader.ui.theme.LocalWindowSizing
import io.github.adkimsm.neteasedownloader.ui.theme.Spacing
import io.github.adkimsm.neteasedownloader.ui.theme.TextPrimary
import io.github.adkimsm.neteasedownloader.ui.theme.TextSecondary

/** 播放队列。当前曲高亮;单曲可移除;点行跳播。行内无封面(D13)。 */
@Composable
fun QueueScreen(
    songs: List<SongEntity>,
    currentIndex: Int,
    onBack: () -> Unit,
    onPlayAt: (Int) -> Unit,
    onRemove: (Long) -> Unit,
) {
    val sizing = LocalWindowSizing.current

    ScreenScaffold(title = stringResource(R.string.queue_title), onBack = onBack) {
        if (songs.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(sizing.gapMd))
                Text(
                    text = stringResource(R.string.queue_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            return@ScreenScaffold
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(songs, key = { _, song -> song.songId }) { index, song ->
                QueueRow(
                    song = song,
                    current = index == currentIndex,
                    onClick = { onPlayAt(index) },
                    onRemove = { onRemove(song.songId) },
                )
            }
        }
    }
}

@Composable
private fun QueueRow(
    song: SongEntity,
    current: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val sizing = LocalWindowSizing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = sizing.menuRowHeight)
            .clip(RoundedCornerShape(6.dp))
            .background(if (current) BrandRedMuted else MaterialTheme.colorScheme.background)
            .clickable(onClick = onClick)
            .padding(start = sizing.gapSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (current) BrandRed else TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(Spacing.xs))
        IconButton(onClick = onRemove, modifier = Modifier.size(sizing.touchTarget)) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.queue_remove),
                tint = TextSecondary,
                modifier = Modifier.size(sizing.iconSize * 0.8f),
            )
        }
    }
}
