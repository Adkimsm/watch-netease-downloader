package io.github.adkimsm.neteasedownloader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.adkimsm.neteasedownloader.R
import io.github.adkimsm.neteasedownloader.library.RemoveOutcome
import io.github.adkimsm.neteasedownloader.library.RemoveReport
import io.github.adkimsm.neteasedownloader.ui.theme.BrandRed
import io.github.adkimsm.neteasedownloader.ui.theme.LocalWindowSizing
import io.github.adkimsm.neteasedownloader.ui.theme.StateError
import io.github.adkimsm.neteasedownloader.ui.theme.SurfaceLevel2
import io.github.adkimsm.neteasedownloader.ui.theme.TextPrimary
import io.github.adkimsm.neteasedownloader.ui.theme.TextSecondary
import kotlinx.coroutines.delay

/** 结果条存活时长:足够点中"撤销",又不至于长期占着屏幕 */
private const val BANNER_DURATION_MS = 3000L

/**
 * 删除后的结果条。
 *
 * 这是"点了就删"(不弹确认框)的补偿手段:3 秒内可以一键把歌单与红心恢复回来。
 * 本地文件不在撤销范围内 —— 它由下一次同步下回,当场重下会让撤销按钮卡住几十秒。
 */
@Composable
fun DeleteResultBanner(
    songName: String,
    outcome: RemoveOutcome,
    report: RemoveReport,
    onUndo: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sizing = LocalWindowSizing.current

    LaunchedEffect(report.songId) {
        delay(BANNER_DURATION_MS)
        onDismiss()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = sizing.bannerMinHeight)
            .clip(RoundedCornerShape(6.dp))
            .background(if (report.hasFailure) StateError.copy(alpha = 0.16f) else SurfaceLevel2)
            .padding(start = sizing.gapSm, end = sizing.gapSm / 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = bannerText(songName, outcome, report),
                style = MaterialTheme.typography.bodySmall,
                color = if (report.hasFailure) StateError else TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (outcome.unlike && report.unlikeOk == true) {
                Text(
                    text = stringResource(R.string.remove_banner_unliked),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(sizing.gapSm))
        if (report.hasFailure) {
            TextButton(onClick = onRetry) {
                Text(text = stringResource(R.string.common_retry), color = BrandRed)
            }
        } else {
            TextButton(onClick = onUndo) {
                Text(text = stringResource(R.string.remove_banner_undo), color = BrandRed)
            }
        }
    }
}

@Composable
private fun bannerText(songName: String, outcome: RemoveOutcome, report: RemoveReport): String {
    val remoteCount = outcome.remoteTargets.size
    return when {
        report.remoteFailed.isNotEmpty() ->
            stringResource(R.string.remove_banner_partial_failed, report.remoteFailed.size)

        report.remoteStale.isNotEmpty() ->
            stringResource(R.string.remove_banner_stale, report.remoteStale.size)

        remoteCount > 0 && outcome.deleteLocal ->
            stringResource(R.string.remove_banner_done_with_playlists, songName, remoteCount)

        remoteCount > 0 ->
            stringResource(R.string.remove_banner_playlists_only, songName, remoteCount)

        outcome.deleteLocal -> stringResource(R.string.remove_banner_local_only, songName)

        else -> stringResource(R.string.remove_banner_noop, songName)
    }
}
