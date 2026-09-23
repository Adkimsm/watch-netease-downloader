package io.github.adkimsm.neteasedownloader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.github.adkimsm.neteasedownloader.R
import io.github.adkimsm.neteasedownloader.ui.components.ConfirmDialog
import io.github.adkimsm.neteasedownloader.ui.components.HDivider
import io.github.adkimsm.neteasedownloader.ui.components.ScreenScaffold
import io.github.adkimsm.neteasedownloader.ui.theme.LocalWindowSizing
import io.github.adkimsm.neteasedownloader.ui.theme.TextPrimary
import androidx.compose.foundation.layout.fillMaxWidth

/**
 * 歌单级操作菜单(重命名 / 删除歌单)。
 *
 * 删除整个歌单**不可撤销**,保留确认框 —— 「点了就删」只适用于删单曲;
 * 一个歌单几千首歌,误删的代价不是一个 3 秒撤销条兜得住的。
 */
@Composable
fun PlaylistMenuScreen(
    playlistName: String,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val sizing = LocalWindowSizing.current
    var confirmDelete by remember { mutableStateOf(false) }

    ScreenScaffold(title = stringResource(R.string.playlist_menu_title), onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = playlistName,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.size(sizing.gapSm))
            HDivider()
            Spacer(Modifier.size(sizing.gapSm))

            ActionRow(
                icon = Icons.Filled.Create,
                label = stringResource(R.string.playlist_rename),
                trailing = null,
                onClick = onRename,
                chevron = true,
            )
            Spacer(Modifier.size(sizing.gapSm / 2))
            ActionRow(
                icon = Icons.Filled.DeleteOutline,
                label = stringResource(R.string.playlist_delete),
                trailing = null,
                onClick = { confirmDelete = true },
                destructive = true,
            )
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.playlist_delete_title),
            message = stringResource(R.string.playlist_delete_message, playlistName),
            confirmText = stringResource(R.string.playlist_delete),
            destructive = true,
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}