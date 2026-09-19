package io.github.adkimsm.neteasedownloader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.launch

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

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "诊断日志",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onClear) { Text("清空") }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            items(logs) { line ->
                Text(
                    line,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 11.sp,
                )
            }
        }
        logFilePath?.let { path ->
            Spacer(Modifier.height(2.dp))
            Text(
                path,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                scope.launch {
                    clipboard.setText(AnnotatedString(logs.joinToString("\n")))
                }
            },
            enabled = logs.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("复制全部")
        }
        Spacer(Modifier.height(4.dp))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("返回")
        }
    }
}