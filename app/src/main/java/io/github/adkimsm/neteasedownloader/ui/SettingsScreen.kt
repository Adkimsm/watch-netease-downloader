package io.github.adkimsm.neteasedownloader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.adkimsm.neteasedownloader.R
import io.github.adkimsm.neteasedownloader.data.SettingsStore
import io.github.adkimsm.neteasedownloader.library.RemoveScope
import io.github.adkimsm.neteasedownloader.ui.components.ConfirmDialog
import io.github.adkimsm.neteasedownloader.ui.components.CheckMark
import io.github.adkimsm.neteasedownloader.ui.components.HDivider
import io.github.adkimsm.neteasedownloader.ui.components.ListRow
import io.github.adkimsm.neteasedownloader.ui.components.PrimaryButton
import io.github.adkimsm.neteasedownloader.ui.components.ScreenScaffold
import io.github.adkimsm.neteasedownloader.ui.components.SecondaryButton
import io.github.adkimsm.neteasedownloader.ui.components.SectionLabel
import io.github.adkimsm.neteasedownloader.ui.theme.BrandRed
import io.github.adkimsm.neteasedownloader.ui.theme.LocalWindowSizing
import io.github.adkimsm.neteasedownloader.ui.theme.Spacing
import io.github.adkimsm.neteasedownloader.ui.theme.StateWarn
import io.github.adkimsm.neteasedownloader.ui.theme.TextDisabled
import io.github.adkimsm.neteasedownloader.ui.theme.TextPrimary
import io.github.adkimsm.neteasedownloader.ui.theme.TextSecondary

/**
 * 设置页。
 *
 * 按「播放器优先」分组:播放 / 管理 / 同步 / 通用。同步(下载音质 + 已勾选统计 +
 * 「立即同步」)作为维护性动作放在中段,不再是首页主角。
 * 音质档位纵向单列:4 个 chip 在手表宽度下必然换行/溢出,
 * 且无法容纳带副标题的说明(无损需要提示占用存储,对应 PLAN §1.5 的存储预警)。
 */
@Composable
fun SettingsScreen(
    currentLevel: String,
    onLevelChange: (String) -> Unit,
    currentStreamLevel: String,
    onStreamLevelChange: (String) -> Unit,
    onBack: () -> Unit,
    removeScope: RemoveScope,
    onRemoveScopeChange: (RemoveScope) -> Unit,
    onLogout: () -> Unit,
    onDiagnostics: () -> Unit,
    onSyncClick: () -> Unit,
    loggingOut: Boolean = false,
    levelJustChanged: Boolean = false,
    playlistCount: Int = 0,
    enabledPlaylistCount: Int = 0,
    unlockDownload: Boolean,
    onUnlockDownloadChange: (Boolean) -> Unit,
    unlockStream: Boolean,
    onUnlockStreamChange: (Boolean) -> Unit,
    providerKuwo: Boolean,
    onProviderKuwoChange: (Boolean) -> Unit,
    providerKugou: Boolean,
    onProviderKugouChange: (Boolean) -> Unit,
    spoofRealIp: Boolean,
    onSpoofRealIpChange: (Boolean) -> Unit,
    /** 离线删除排队中的曲目数:> 0 时给出「清空」出口 */
    pendingRemovalCount: Int = 0,
    onClearPendingRemovals: () -> Unit = {},
) {
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmClearPending by remember { mutableStateOf(false) }
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
            // 播放:在线播放音质是播放器的第一优先级
            SectionLabel(text = stringResource(R.string.settings_section_play))
            Spacer(Modifier.height(sizing.gapSm))
            Text(
                text = stringResource(R.string.settings_stream_quality),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            Spacer(Modifier.height(sizing.gapSm))

            SettingsStore.LEVELS.forEachIndexed { index, level ->
                QualityOption(
                    level = level,
                    selected = level == currentStreamLevel,
                    loading = false,
                    showCheck = level == currentStreamLevel && levelJustChanged,
                    // 最后一项不画分隔线:紧随其后已是分区分隔线
                    showDivider = index < SettingsStore.LEVELS.lastIndex,
                    onClick = { onStreamLevelChange(level) },
                )
            }

            Spacer(Modifier.height(sizing.gapMd))
            HDivider()
            Spacer(Modifier.height(sizing.gapMd))

            // 管理:删除歌曲时的行为
            SectionLabel(text = stringResource(R.string.settings_section_manage))
            Spacer(Modifier.height(sizing.gapSm))
            Text(
                text = stringResource(R.string.settings_remove_scope),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            Spacer(Modifier.height(sizing.gapSm))

            RemoveScope.entries.forEachIndexed { index, scope ->
                ScopeOption(
                    scope = scope,
                    selected = scope == removeScope,
                    showDivider = index < RemoveScope.entries.lastIndex,
                    onClick = { onRemoveScopeChange(scope) },
                )
            }

            Spacer(Modifier.height(sizing.gapMd))
            HDivider()
            Spacer(Modifier.height(sizing.gapMd))

            // 同步:维护性动作,不再是首页主角
            SectionLabel(text = stringResource(R.string.settings_section_sync))
            Spacer(Modifier.height(sizing.gapSm))
            Text(
                text = stringResource(R.string.settings_quality_download),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            Spacer(Modifier.height(sizing.gapSm))

            SettingsStore.LEVELS.forEachIndexed { index, level ->
                QualityOption(
                    level = level,
                    selected = level == currentLevel,
                    // 音质写入很快,不做内联转圈;切换成功后短暂显示对勾作为确认
                    loading = false,
                    showCheck = level == currentLevel && levelJustChanged,
                    showDivider = index < SettingsStore.LEVELS.lastIndex,
                    onClick = { onLevelChange(level) },
                )
            }

            if (playlistCount > 0) {
                Spacer(Modifier.height(sizing.gapSm))
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

            Spacer(Modifier.height(sizing.gapMd))
            PrimaryButton(
                text = stringResource(R.string.settings_sync_now),
                onClick = onSyncClick,
            )

            Spacer(Modifier.height(sizing.gapMd))
            HDivider()
            Spacer(Modifier.height(sizing.gapMd))
            // 解锁:灰色歌曲改用第三方音源。下载与在线播放各自独立 ——
            // 串流不落盘、关掉即可恢复;下载会产出永久文件,所以分开给开关。
            SectionLabel(text = stringResource(R.string.settings_section_unlock))
            Spacer(Modifier.height(sizing.gapSm))

            ToggleOption(
                title = stringResource(R.string.settings_unlock_download),
                description = stringResource(R.string.settings_unlock_download_desc),
                checked = unlockDownload,
                showDivider = true,
                onClick = { onUnlockDownloadChange(!unlockDownload) },
            )
            ToggleOption(
                title = stringResource(R.string.settings_unlock_stream),
                description = stringResource(R.string.settings_unlock_stream_desc),
                checked = unlockStream,
                showDivider = true,
                onClick = { onUnlockStreamChange(!unlockStream) },
            )

            // 两个替换开关都关着时,音源开关没有意义 → 置灰
            val providersEnabled = unlockDownload || unlockStream
            ToggleOption(
                title = stringResource(R.string.settings_provider_kuwo),
                description = stringResource(R.string.settings_provider_desc),
                checked = providerKuwo,
                enabled = providersEnabled,
                showDivider = true,
                onClick = { onProviderKuwoChange(!providerKuwo) },
            )
            ToggleOption(
                title = stringResource(R.string.settings_provider_kugou),
                description = null,
                checked = providerKugou,
                enabled = providersEnabled,
                showDivider = true,
                onClick = { onProviderKugouChange(!providerKugou) },
            )
            ToggleOption(
                title = stringResource(R.string.settings_spoof_real_ip),
                description = stringResource(R.string.settings_spoof_real_ip_desc),
                checked = spoofRealIp,
                showDivider = false,
                onClick = { onSpoofRealIpChange(!spoofRealIp) },
            )

            Spacer(Modifier.height(sizing.gapMd))
            HDivider()
            Spacer(Modifier.height(sizing.gapMd))

            // 通用:诊断与账号
            SectionLabel(text = stringResource(R.string.settings_section_general))
            Spacer(Modifier.height(sizing.gapSm))
            if (pendingRemovalCount > 0) {
                // 队列是「删了一半」的中间态:要么让它执行,要么让用户能整体反悔
                SecondaryButton(
                    text = stringResource(R.string.pending_removal_clear, pendingRemovalCount),
                    onClick = { confirmClearPending = true },
                    danger = true,
                )
                Spacer(Modifier.height(sizing.gapSm))
            }
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

    if (confirmClearPending) {
        ConfirmDialog(
            title = stringResource(R.string.pending_removal_clear_title),
            message = stringResource(R.string.pending_removal_clear_message, pendingRemovalCount),
            confirmText = stringResource(R.string.pending_removal_clear_confirm),
            destructive = true,
            onConfirm = {
                confirmClearPending = false
                onClearPendingRemovals()
            },
            onDismiss = { confirmClearPending = false },
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
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    val sizing = LocalWindowSizing.current
    // 选中态靠左侧红竖条 + 红淡底,不靠描边
    ListRow(
        minHeight = sizing.touchTarget,
        selected = selected,
        enabled = !loading,
        onClick = onClick,
        role = Role.RadioButton,
        showDivider = showDivider,
        contentPadding = PaddingValues(horizontal = 0.dp),
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
            CheckMark(color = BrandRed, size = sizing.iconSize)
            Spacer(Modifier.width(Spacing.sm))
        } else {
            Spacer(Modifier.width(Spacing.sm))
        }
    }
}

/** 删除模式的一档 */
@Composable
private fun ScopeOption(
    scope: RemoveScope,
    selected: Boolean,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    val sizing = LocalWindowSizing.current
    ListRow(
        minHeight = sizing.touchTarget,
        selected = selected,
        onClick = onClick,
        role = Role.RadioButton,
        showDivider = showDivider,
        contentPadding = PaddingValues(horizontal = 0.dp),
    ) {
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
                text = scopeTitle(scope),
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
            )
            Text(
                text = scopeDescription(scope),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
    }
}
/**
 * 布尔设置项。
 *
 * 与音质/删除档位共用同一套视觉语言(左红竖条 + 选中底色),但**多一个开/关文字**:
 * 单选组里"选中项"不言自明,开关上"现在到底是开还是关"必须一眼可见。
 */
@Composable
private fun ToggleOption(
    title: String,
    description: String?,
    checked: Boolean,
    showDivider: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val sizing = LocalWindowSizing.current
    ListRow(
        minHeight = sizing.touchTarget,
        selected = checked,
        enabled = enabled,
        onClick = onClick,
        role = Role.Switch,
        showDivider = showDivider,
        contentPadding = PaddingValues(horizontal = 0.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(sizing.touchTarget)
                .background(
                    if (checked && enabled) BrandRed else MaterialTheme.colorScheme.background,
                ),
        )
        Spacer(Modifier.width(Spacing.sm))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = Spacing.xs),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) TextPrimary else TextDisabled,
            )
            if (!description.isNullOrEmpty()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        }
        Text(
            text = stringResource(if (checked) R.string.common_on else R.string.common_off),
            style = MaterialTheme.typography.bodySmall,
            color = when {
                !enabled -> TextDisabled
                checked -> BrandRed
                else -> TextSecondary
            },
        )
        Spacer(Modifier.width(Spacing.sm))
    }
}

@Composable
private fun scopeTitle(scope: RemoveScope): String = stringResource(
    when (scope) {
        RemoveScope.ASK -> R.string.settings_remove_ask
        RemoveScope.ALL -> R.string.settings_remove_all
        RemoveScope.LOCAL_ONLY -> R.string.settings_remove_local
    },
)

@Composable
private fun scopeDescription(scope: RemoveScope): String = stringResource(
    when (scope) {
        RemoveScope.ASK -> R.string.settings_remove_ask_desc
        RemoveScope.ALL -> R.string.settings_remove_all_desc
        RemoveScope.LOCAL_ONLY -> R.string.settings_remove_local_desc
    },
)

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
