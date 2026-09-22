package io.github.adkimsm.neteasedownloader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.adkimsm.neteasedownloader.R
import io.github.adkimsm.neteasedownloader.library.PresenceEntry
import io.github.adkimsm.neteasedownloader.library.RemoveSelection
import io.github.adkimsm.neteasedownloader.library.SongPresence
import io.github.adkimsm.neteasedownloader.library.defaultSelection
import io.github.adkimsm.neteasedownloader.library.planFor
import io.github.adkimsm.neteasedownloader.library.removeTargetCount
import io.github.adkimsm.neteasedownloader.library.RemoveScope
import io.github.adkimsm.neteasedownloader.ui.components.HDivider
import io.github.adkimsm.neteasedownloader.ui.components.PrimaryButton
import io.github.adkimsm.neteasedownloader.ui.components.ScreenScaffold
import io.github.adkimsm.neteasedownloader.ui.components.SecondaryButton
import io.github.adkimsm.neteasedownloader.ui.components.TrackSkeletonList
import io.github.adkimsm.neteasedownloader.ui.theme.LocalWindowSizing
import io.github.adkimsm.neteasedownloader.ui.theme.Spacing
import io.github.adkimsm.neteasedownloader.ui.theme.StateError
import io.github.adkimsm.neteasedownloader.ui.theme.StateWarn
import io.github.adkimsm.neteasedownloader.ui.theme.SurfaceLevel1
import io.github.adkimsm.neteasedownloader.ui.theme.TextDisabled
import io.github.adkimsm.neteasedownloader.ui.theme.TextPrimary
import io.github.adkimsm.neteasedownloader.ui.theme.TextSecondary

/**
 * 删除面板:这首歌在哪些歌单里,用户勾选要从哪些删掉。
 *
 * 手表上**整屏**呈现而不是对话框:Compact 档一行歌单就要占掉近 40dp,
 * 三五行的对话框会把确认按钮直接顶出屏幕。
 *
 * 关键设计是**后果预览实时更新**:勾掉一个歌单,下方"下次同步会重新下载"的提示
 * 立刻跟着变。用户不必删完才发现歌又回来了。
 */
@Composable
fun RemoveSongSheet(
    presence: SongPresence?,
    loading: Boolean,
    inFlight: Boolean,
    selection: RemoveSelection,
    onSelectionChange: (RemoveSelection) -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    val sizing = LocalWindowSizing.current

    ScreenScaffold(
        title = stringResource(R.string.remove_title),
        onBack = onBack,
    ) {
        if (loading || presence == null) {
            // 骨架屏直接在 ScreenScaffold 的 Column 里,不套 weight(那不是 ColumnScope)
            TrackSkeletonList()
            return@ScreenScaffold
        }

        val outcome = planFor(RemoveScope.ASK, presence, selection)

        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = presence.songName,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(sizing.gapSm))

                // 红心是自动取消的,只做只读提示 —— 用户不需要(也不该)在这里做决定
                if (presence.liked) {
                    HintRow(
                        text = stringResource(R.string.remove_liked_hint),
                        color = StateWarn,
                    )
                    Spacer(Modifier.height(sizing.gapSm))
                }

                if (presence.playlists.isNotEmpty()) {
                    HDivider()
                    Spacer(Modifier.height(sizing.gapSm))
                    Text(
                        text = stringResource(R.string.remove_playlist_scope, presence.playlists.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(sizing.gapSm / 2))

                    presence.playlists.forEach { entry ->
                        PlaylistPickRow(
                            entry = entry,
                            checked = entry.playlistId in selection.playlistIds,
                            // 他人歌单接口会拒绝,置灰并说明原因,而不是让用户勾了才失败
                            enabled = entry.owned && !selection.localOnly,
                            onToggle = { checked ->
                                val next = if (checked) {
                                    selection.playlistIds + entry.playlistId
                                } else {
                                    selection.playlistIds - entry.playlistId
                                }
                                onSelectionChange(selection.copy(playlistIds = next))
                            },
                        )
                    }
                    Spacer(Modifier.height(sizing.gapSm))
                }

                HDivider()
                Spacer(Modifier.height(sizing.gapSm))

                // 两个互斥开关:勾一个自动取消另一个
                ToggleRow(
                    text = stringResource(R.string.remove_local_only),
                    checked = selection.localOnly,
                    onToggle = {
                        onSelectionChange(
                            selection.copy(localOnly = !selection.localOnly, keepLocal = false),
                        )
                    },
                )
                Spacer(Modifier.height(sizing.gapSm / 2))
                ToggleRow(
                    text = stringResource(R.string.remove_keep_local),
                    checked = selection.keepLocal,
                    onToggle = {
                        onSelectionChange(
                            selection.copy(keepLocal = !selection.keepLocal, localOnly = false),
                        )
                    },
                )

                if (outcome.survivingEnabled.isNotEmpty()) {
                    Spacer(Modifier.height(sizing.gapSm))
                    HintRow(
                        text = stringResource(
                            R.string.remove_will_redownload,
                            outcome.survivingEnabled.joinToString("、") { it.name },
                        ),
                        color = StateWarn,
                    )
                }
            }

            Spacer(Modifier.height(sizing.gapSm))
            PrimaryButton(
                text = confirmLabel(outcome.remoteTargets.isNotEmpty(), removeTargetCount(outcome), outcome.deleteLocal),
                onClick = onConfirm,
                enabled = !inFlight,
                loading = inFlight,
            )
            Spacer(Modifier.height(sizing.gapSm))
            SecondaryButton(text = stringResource(R.string.common_cancel), onClick = onBack)
        }
    }
}

@Composable
private fun confirmLabel(hasRemote: Boolean, remoteCount: Int, deleteLocal: Boolean): String = when {
    hasRemote && deleteLocal -> stringResource(R.string.remove_confirm_with_playlists, remoteCount)
    hasRemote -> stringResource(R.string.remove_confirm_playlists_only, remoteCount)
    deleteLocal -> stringResource(R.string.remove_confirm_local_only)
    else -> stringResource(R.string.remove_confirm_nothing)
}

@Composable
private fun PlaylistPickRow(
    entry: PresenceEntry,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val sizing = LocalWindowSizing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = sizing.menuRowHeight)
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled) { onToggle(!checked) }
            .padding(end = sizing.gapSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(sizing.touchTarget * 0.75f), contentAlignment = Alignment.Center) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle(it) },
                enabled = enabled,
                modifier = Modifier.size(sizing.iconSize),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) TextPrimary else TextDisabled,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!entry.owned) {
                Text(
                    text = stringResource(R.string.remove_not_owned),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextDisabled,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 只读提示行 */
@Composable
private fun HintRow(text: String, color: androidx.compose.ui.graphics.Color) {
    val sizing = LocalWindowSizing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(SurfaceLevel1)
            .padding(sizing.gapSm),
    ) {
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun ToggleRow(text: String, checked: Boolean, onToggle: () -> Unit) {
    val sizing = LocalWindowSizing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = sizing.menuRowHeight)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onToggle)
            .padding(end = sizing.gapSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(sizing.touchTarget * 0.75f), contentAlignment = Alignment.Center) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
                modifier = Modifier.size(sizing.iconSize),
            )
        }
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 面板默认选择(供调用方在进入前初始化) */
fun initialSelectionFor(presence: SongPresence): RemoveSelection = defaultSelection(presence)
