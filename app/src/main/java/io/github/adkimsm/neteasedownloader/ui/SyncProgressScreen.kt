package io.github.adkimsm.neteasedownloader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import io.github.adkimsm.neteasedownloader.R
import io.github.adkimsm.neteasedownloader.sync.SyncEngine
import io.github.adkimsm.neteasedownloader.ui.components.ConfirmDialog
import io.github.adkimsm.neteasedownloader.ui.components.ScreenScaffold
import io.github.adkimsm.neteasedownloader.ui.components.SecondaryButton
import io.github.adkimsm.neteasedownloader.ui.theme.BrandRed
import io.github.adkimsm.neteasedownloader.ui.theme.BrandRedMuted
import io.github.adkimsm.neteasedownloader.ui.theme.LocalWindowSizing
import io.github.adkimsm.neteasedownloader.ui.theme.Spacing
import io.github.adkimsm.neteasedownloader.ui.theme.TextPrimary
import io.github.adkimsm.neteasedownloader.ui.theme.TextSecondary
import io.github.adkimsm.neteasedownloader.ui.theme.WindowClass

/**
 * 同步进度页(下载页)。
 *
 * 同时承载下载/清理与「拉取+差量」两个阶段 ——
 * 后者可能持续数分钟(3742 首的歌单),原先留在歌单页只有底部一个小转圈。
 *
 * 大号百分比 + ETA(formatted from startedAt)+ 停止二次确认。
 *
 * 小屏适配要点:
 *  - 停止按钮原先靠 `Spacer(weight(1f))` 顶到底部。纵向内容在矮屏上会超出可用高度,
 *    按钮被挤出可视区且无法滚动 → 改为「内容区可滚动 + 按钮始终固定可见」,
 *    保证任何窗口尺寸下停止都是可达的。
 *  - 极窄屏把总数并进百分比同一行,并把大数字降一档,节省两行高度。
 */
@Composable
fun SyncProgressScreen(progress: SyncEngine.Progress, onStop: () -> Unit) {
    var confirmStop by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }

    // 离开进度屏时复位,避免下次进入残留"正在停止"
    LaunchedEffect(progress.stage) {
        if (progress.stage != SyncEngine.Stage.DOWNLOADING &&
            progress.stage != SyncEngine.Stage.DELETING &&
            progress.stage != SyncEngine.Stage.REFRESHING
        ) {
            stopping = false
        }
    }

    val percent = progressPercent(progress.done, progress.total)
    val fraction = progressFraction(progress.done, progress.total)

    // ETA:滑动窗口估算。样本不足时不显示,而不是显示乱算的数字。
    val estimator = remember { EtaEstimator() }
    LaunchedEffect(progress.stage) {
        if (progress.stage == SyncEngine.Stage.DOWNLOADING) estimator.reset()
    }
    var etaText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(progress.done, progress.total, progress.startedAt) {
        if (progress.stage == SyncEngine.Stage.DOWNLOADING && progress.done > 0) {
            val now = System.currentTimeMillis()
            estimator.record(progress.done, now)
            etaText = estimator.estimateSeconds(progress.done, progress.total, now)
                ?.let { formatEta(it) }
        } else {
            etaText = null
        }
    }

    val sizing = LocalWindowSizing.current
    val compact = sizing.windowClass == WindowClass.Compact

    ScreenScaffold(title = stageTitle(progress.stage)) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 上半部分可滚动:矮屏上内容再高也不会把停止按钮顶出屏幕
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(sizing.gapMd))

                if (percent != null) {
                    Text(
                        text = if (compact) {
                            // 极窄屏:百分比与总数合并成一行,省一行高度
                            stringResource(
                                R.string.progress_percent_with_total,
                                percent,
                                progress.done,
                                progress.total,
                            )
                        } else {
                            "$percent%"
                        },
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontSize = sizing.progressPercentSp.sp,
                            lineHeight = (sizing.progressPercentSp + 6).sp,
                            fontWeight = FontWeight.W500,
                        ),
                        color = TextPrimary,
                    )
                    if (!compact) {
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            text = stringResource(
                                R.string.progress_done_of_total,
                                progress.done,
                                progress.total,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                }

                Spacer(Modifier.height(sizing.gapMd))
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(sizing.progressBar)
                            .clip(RoundedCornerShape(sizing.progressBar / 2)),
                        color = BrandRed,
                        trackColor = BrandRedMuted,
                    )
                } else {
                    // 无分母阶段(拉取/清理):不确定态进度条
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(sizing.progressBar)
                            .clip(RoundedCornerShape(sizing.progressBar / 2)),
                        color = BrandRed,
                        trackColor = BrandRedMuted,
                    )
                }

                Spacer(Modifier.height(sizing.gapMd))
                Text(
                    text = progress.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = if (compact) 3 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = sizing.gapSm),
                )

                etaText?.let { eta ->
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = stringResource(R.string.progress_eta_label, eta),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextPrimary,
                    )
                }
            }

            // 停止按钮固定在最下方,始终可达(不再被纵向内容挤出屏幕)
            Spacer(Modifier.height(sizing.gapSm))
            SecondaryButton(
                text = if (stopping) {
                    stringResource(R.string.progress_stopping)
                } else {
                    stringResource(R.string.progress_stop)
                },
                onClick = { confirmStop = true },
                loading = stopping,
                danger = true,
            )
        }
    }

    if (confirmStop) {
        ConfirmDialog(
            title = stringResource(R.string.progress_stop_title),
            message = stringResource(R.string.progress_stop_message),
            confirmText = stringResource(R.string.progress_stop_confirm),
            destructive = true,
            onConfirm = {
                confirmStop = false
                stopping = true
                onStop()
            },
            onDismiss = { confirmStop = false },
        )
    }
}

/** 阶段标题:把引擎的阶段翻译成用户能懂的动作 */
@Composable
private fun stageTitle(stage: SyncEngine.Stage): String = when (stage) {
    SyncEngine.Stage.REFRESHING -> stringResource(R.string.progress_stage_refreshing)
    SyncEngine.Stage.DOWNLOADING -> stringResource(R.string.progress_stage_downloading)
    SyncEngine.Stage.DELETING -> stringResource(R.string.progress_stage_deleting)
    SyncEngine.Stage.DONE -> stringResource(R.string.progress_completed)
    else -> stringResource(R.string.progress_title)
}
