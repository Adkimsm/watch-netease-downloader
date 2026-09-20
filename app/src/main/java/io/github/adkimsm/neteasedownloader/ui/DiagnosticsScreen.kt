package io.github.adkimsm.neteasedownloader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.adkimsm.neteasedownloader.R
import io.github.adkimsm.neteasedownloader.ui.components.PrimaryButton
import io.github.adkimsm.neteasedownloader.ui.components.ScreenScaffold
import io.github.adkimsm.neteasedownloader.ui.theme.Dimens
import io.github.adkimsm.neteasedownloader.ui.theme.Spacing
import io.github.adkimsm.neteasedownloader.ui.theme.StateError
import io.github.adkimsm.neteasedownloader.ui.theme.StateInfo
import io.github.adkimsm.neteasedownloader.ui.theme.StateWarn
import io.github.adkimsm.neteasedownloader.ui.theme.TextDisabled
import io.github.adkimsm.neteasedownloader.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * 诊断日志页。
 *
 * 操作按钮从原来的三个全宽 Button 改为顶栏图标 + 底部主按钮,给日志列表让出高度。
 * 日志按级别着色 —— 只判断行首级别字符,不解析行内内容,1000 行开销可忽略。
 */
@Composable
fun DiagnosticsScreen(
    logs: List<String>,
    logFilePath: String?,
    onBack: () -> Unit,
    onClear: () -> Unit,
) {
    val listState = rememberLazyListState()
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.scrollToItem(logs.lastIndex)
    }

    ScreenScaffold(
        title = stringResource(R.string.diagnostics_title),
        onBack = onBack,
        action = {
            IconButton(
                onClick = onClear,
                modifier = Modifier.size(Dimens.TouchTarget),
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.common_clear),
                    tint = TextSecondary,
                )
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.diagnostics_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    items(logs) { line ->
                        Text(
                            text = line,
                            fontSize = Dimens.DiagnosticsMono,
                            lineHeight = Dimens.DiagnosticsMonoLineHeight,
                            fontFamily = FontFamily.Monospace,
                            color = levelColor(line),
                        )
                    }
                }
            }

            logFilePath?.let { path ->
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = path,
                    fontSize = Dimens.DiagnosticsMono,
                    color = TextDisabled,
                    maxLines = 1,
                )
            }

            Spacer(Modifier.height(Spacing.sm))
            PrimaryButton(
                text = stringResource(R.string.diagnostics_copy_all),
                onClick = {
                    scope.launch {
                        clipboard.setText(AnnotatedString(logs.joinToString("\n")))
                    }
                },
                enabled = logs.isNotEmpty(),
            )
        }
    }
}

/**
 * 按行首级别字符着色。
 * 格式为 "MM-dd HH:mm:ss.SSS <级别>/<Tag>: ...",故取行内首个 " X/" 出现的位置判断。
 */
private fun levelColor(line: String): Color = when {
    line.contains(" E/") -> StateError
    line.contains(" W/") -> StateWarn
    line.contains(" I/") -> StateInfo
    else -> TextSecondary
}
