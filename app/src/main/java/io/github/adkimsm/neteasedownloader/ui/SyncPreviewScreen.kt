package io.github.adkimsm.neteasedownloader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.adkimsm.neteasedownloader.R
import io.github.adkimsm.neteasedownloader.sync.SyncEngine
import io.github.adkimsm.neteasedownloader.ui.components.PrimaryButton
import io.github.adkimsm.neteasedownloader.ui.components.ScreenScaffold
import io.github.adkimsm.neteasedownloader.ui.components.SecondaryButton
import io.github.adkimsm.neteasedownloader.ui.components.StatRow
import io.github.adkimsm.neteasedownloader.ui.components.StatSkeleton
import io.github.adkimsm.neteasedownloader.ui.theme.BrandRed
import io.github.adkimsm.neteasedownloader.ui.theme.BrandRedMuted
import io.github.adkimsm.neteasedownloader.ui.theme.LocalWindowSizing
import io.github.adkimsm.neteasedownloader.ui.theme.Spacing
import io.github.adkimsm.neteasedownloader.ui.theme.StateError
import io.github.adkimsm.neteasedownloader.ui.theme.StateWarn
import io.github.adkimsm.neteasedownloader.ui.theme.SurfaceLevel1
import io.github.adkimsm.neteasedownloader.ui.theme.TextPrimary
import io.github.adkimsm.neteasedownloader.ui.theme.TextSecondary

/**
 * 同步预览页。
 *
 * 修正原实现的两处问题:
 *  1. 体积用 formatBytes 而非 `bytes / MB` 整数除法(小于 1MB 会显示 0MB)
 *  2. 存储占用与可用空间并排成独立卡片 + 容量条,空间不足时前置禁用确认
 */
@Composable
fun SyncPreviewScreen(
    diff: SyncEngine.Diff,
    onConfirm: () -> Unit,
    onDiscard: () -> Unit,
) {
    val sizing = LocalWindowSizing.current
    val storageShort = isStorageShort(diff.estimatedBytes, diff.availableBytes)
    val hasChanges = diff.toDownload.isNotEmpty() || diff.toDelete.isNotEmpty()

    ScreenScaffold(title = stringResource(R.string.preview_title)) {
        Column(modifier = Modifier.fillMaxSize()) {

            if (diff.toDownload.isEmpty() && diff.toDelete.isEmpty()) {
                // 计算尚未出结果时的占位(REFRESHING 阶段的过渡)
                StatSkeleton(rows = 3)
            } else {
                StatRow(
                    label = stringResource(R.string.preview_to_download),
                    value = stringResource(R.string.preview_count_unit, diff.toDownload.size),
                    valueColor = BrandRed,
                    emphasized = true,
                )
                Spacer(Modifier.height(sizing.gapSm / 2))
                StatRow(
                    label = stringResource(R.string.preview_to_delete),
                    value = stringResource(R.string.preview_count_unit, diff.toDelete.size),
                    valueColor = if (diff.toDelete.isNotEmpty()) StateWarn else TextSecondary,
                )
                Spacer(Modifier.height(sizing.gapSm / 2))
                // 恒显示,避免 0 -> N 时布局跳动
                StatRow(
                    label = stringResource(R.string.preview_skipped),
                    value = stringResource(R.string.preview_count_unit, diff.missingUrlCount),
                    valueColor = if (diff.missingUrlCount > 0) StateWarn else TextSecondary,
                )
            }

            Spacer(Modifier.height(sizing.gapMd))
            StorageCard(diff = diff, shortage = storageShort)
            Spacer(Modifier.weight(1f))

            PrimaryButton(
                text = when {
                    !hasChanges -> stringResource(R.string.preview_no_changes)
                    diff.toDelete.isNotEmpty() -> stringResource(
                        R.string.preview_confirm_with_delete,
                        diff.toDelete.size,
                    )
                    else -> stringResource(R.string.preview_confirm)
                },
                onClick = onConfirm,
                // 无变更或空间不足都不放行:与引擎的 StorageShortageException 前置一致
                enabled = hasChanges && !storageShort,
            )
            Spacer(Modifier.height(sizing.gapSm))
            SecondaryButton(
                text = stringResource(R.string.common_back),
                onClick = onDiscard,
            )
        }
    }
}

/** 存储卡片:占用 / 可用 + 容量条 */
@Composable
private fun StorageCard(diff: SyncEngine.Diff, shortage: Boolean) {
    val sizing = LocalWindowSizing.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(SurfaceLevel1)
            .padding(Spacing.sm),
    ) {
        Text(
            text = stringResource(
                R.string.preview_storage,
                formatBytes(diff.estimatedBytes),
                formatBytes(diff.availableBytes),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (shortage) StateError else TextPrimary,
        )

        if (diff.availableBytes > 0) {
            Spacer(Modifier.height(sizing.gapSm))
            val ratio = (diff.estimatedBytes.toFloat() / diff.availableBytes)
                .coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(BrandRedMuted),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(ratio)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (shortage) StateError else BrandRed),
                )
            }
        }

        if (shortage) {
            Spacer(Modifier.height(sizing.gapSm / 2))
            Text(
                text = stringResource(R.string.preview_storage_short),
                style = MaterialTheme.typography.bodySmall,
                color = StateError,
            )
        }
    }
}
