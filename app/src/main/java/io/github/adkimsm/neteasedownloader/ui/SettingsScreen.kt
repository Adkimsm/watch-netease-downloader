package io.github.adkimsm.neteasedownloader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.adkimsm.neteasedownloader.R
import io.github.adkimsm.neteasedownloader.data.SettingsStore
import io.github.adkimsm.neteasedownloader.ui.components.ConfirmDialog
import io.github.adkimsm.neteasedownloader.ui.components.HDivider
import io.github.adkimsm.neteasedownloader.ui.components.ScreenScaffold
import io.github.adkimsm.neteasedownloader.ui.components.SecondaryButton
import io.github.adkimsm.neteasedownloader.ui.theme.BrandRed
import io.github.adkimsm.neteasedownloader.ui.theme.LocalWindowSizing
import io.github.adkimsm.neteasedownloader.ui.theme.Spacing
import io.github.adkimsm.neteasedownloader.ui.theme.StateWarn
import io.github.adkimsm.neteasedownloader.ui.theme.SurfaceLevel1
import io.github.adkimsm.neteasedownloader.ui.theme.TextDisabled
import io.github.adkimsm.neteasedownloader.ui.theme.TextPrimary
import io.github.adkimsm.neteasedownloader.ui.theme.TextSecondary

/**
 * 设置页。
 *
 * 音质档位由横向 FilterChip 改为纵向单列:4 个 chip 在手表宽度下必然换行/溢出,
 * 且无法容纳带副标题的说明(无损需要提示占用存储,对应 PLAN §1.5 的存储预警)。
 */
@Composable
fun SettingsScreen(
    currentLevel: String,
    onLevelChange: (String) -> Unit,
    currentStreamLevel: String,
    onStreamLevelChange: (String) -> Unit,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    onDiagnostics: () -> Unit,
    loggingOut: Boolean = false,
    levelJustChanged: Boolean = false,
    playlistCount: Int = 0,
    enabledPlaylistCount: Int = 0,
) {
    var confirmLogout by remember { mutableStateOf(false) }
    val versionName = rememberAppVersionName()
    val sizing = LocalWindowSizing.current

    ScreenScaffold(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.settings_quality),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            Spacer(Modifier.height(sizing.gapSm))

            SettingsStore.LEVELS.forEach { level ->
                QualityOption(
                    level = level,
                    selected = level == currentLevel,
                    // 音质写入很快,不做内联转圈;切换成功后短暂显示对勾作为确认
                    loading = false,
                    showCheck = level == currentLevel && levelJustChanged,
                    onClick = { onLevelChange(level) },
                )
                Spacer(Modifier.height(sizing.gapSm / 2))
            }

            Spacer(Modifier.height(sizing.gapMd))
            HDivider()
            Spacer(Modifier.height(sizing.gapMd))

            Text(
                text = stringResource(R.string.settings_stream_quality),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            Spacer(Modifier.height(sizing.gapSm))

            SettingsStore.LEVELS.forEach { level ->
                QualityOption(
                    level = level,
                    selected = level == currentStreamLevel,
                    loading = false,
                    showCheck = level == currentStreamLevel && levelJustChanged,
                    onClick = { onStreamLevelChange(level) },
                )
                Spacer(Modifier.height(sizing.gapSm / 2))
            }

            Spacer(Modifier.height(sizing.gapMd))
            HDivider()
            Spacer(Modifier.height(sizing.gapMd))

            SecondaryButton(
                text = stringResource(R.string.settings_diagnostics),
                onClick = onDiagnostics,
            )
            Spacer(Modifier.height(sizing.gapSm))
            SecondaryButton(
                text = stringResource(R.string.settings_logout),
                onClick = { confirmLogout = true },
                loading = loggingOut,
                danger = true,
            )

            Spacer(Modifier.height(sizing.gapMd))
            Text(
                text = stringResource(R.string.settings_version, versionName),
                style = MaterialTheme.typography.bodySmall,
                color = TextDisabled,
            )
            if (playlistCount > 0) {
                Text(
                    text = stringResource(
                        R.string.settings_synced_playlists,
                        enabledPlaylistCount,
                        playlistCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextDisabled,
                )
            }
        }
    }

    if (confirmLogout) {
        ConfirmDialog(
            title = stringResource(R.string.settings_logout_title),
            message = stringResource(R.string.settings_logout_message),
            confirmText = stringResource(R.string.settings_logout),
            destructive = true,
            onConfirm = {
                confirmLogout = false
                onLogout()
            },
            onDismiss = { confirmLogout = false },
        )
    }
}

/** 单个音质档位:选中态左侧品牌红竖条 + 右侧勾 */
@Composable
private fun QualityOption(
    level: String,
    selected: Boolean,
    loading: Boolean,
    showCheck: Boolean,
    onClick: () -> Unit,
) {
    val sizing = LocalWindowSizing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = sizing.touchTarget)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) SurfaceLevel1 else MaterialTheme.colorScheme.background)
            .clickable(enabled = !loading, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 选中的品牌红竖条
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(sizing.touchTarget)
                .background(if (selected) BrandRed else MaterialTheme.colorScheme.background),
        )
        Spacer(Modifier.width(Spacing.sm))

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = Spacing.xs),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = levelTitle(level),
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
            )
            Text(
                text = levelDescription(level),
                style = MaterialTheme.typography.bodySmall,
                // 无损档位提示文件较大,给出存储预警
                color = if (level == SettingsStore.LEVEL_LOSSLESS) StateWarn else TextSecondary,
            )
        }

        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(end = Spacing.sm)
                    .height(sizing.iconSize),
                strokeWidth = 2.dp,
                color = BrandRed,
            )
        } else if (showCheck) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.titleMedium,
                color = BrandRed,
                modifier = Modifier.padding(end = Spacing.sm),
            )
        } else {
            Spacer(Modifier.width(Spacing.sm))
        }
    }
}

@Composable
private fun levelTitle(level: String): String = when (level) {
    SettingsStore.LEVEL_STANDARD -> stringResource(R.string.settings_quality_standard)
    SettingsStore.LEVEL_HIGHER -> stringResource(R.string.settings_quality_higher)
    SettingsStore.LEVEL_EXHIGH -> stringResource(R.string.settings_quality_exhigh)
    SettingsStore.LEVEL_LOSSLESS -> stringResource(R.string.settings_quality_lossless)
    else -> level
}

@Composable
private fun levelDescription(level: String): String = when (level) {
    SettingsStore.LEVEL_STANDARD -> stringResource(R.string.settings_quality_standard_desc)
    SettingsStore.LEVEL_HIGHER -> stringResource(R.string.settings_quality_higher_desc)
    SettingsStore.LEVEL_EXHIGH -> stringResource(R.string.settings_quality_exhigh_desc)
    SettingsStore.LEVEL_LOSSLESS -> stringResource(R.string.settings_quality_lossless_desc)
    else -> ""
}

/**
 * 读取版本号。
 * 项目未启用 BuildConfig(AGP 9 默认关闭),故走 PackageManager,不改构建配置。
 */
@Composable
private fun rememberAppVersionName(): String {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName
        }.getOrNull() ?: "-"
    }
}
