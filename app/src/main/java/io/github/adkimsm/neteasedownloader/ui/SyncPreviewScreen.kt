package io.github.adkimsm.neteasedownloader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.adkimsm.neteasedownloader.sync.SyncEngine

@Composable
fun SyncPreviewScreen(diff: SyncEngine.Diff, onConfirm: () -> Unit, onDiscard: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("同步预览", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("待下载 ${diff.toDownload.size} 首", style = MaterialTheme.typography.bodyMedium)
        Text("待删除 ${diff.toDelete.size} 首", style = MaterialTheme.typography.bodyMedium)
        if (diff.missingUrlCount > 0) {
            Text(
                "跳过 ${diff.missingUrlCount} 首(无版权/VIP)",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "约占用 ${diff.estimatedBytes / MB}MB,可用 ${diff.availableBytes / MB}MB",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onDiscard, modifier = Modifier.weight(1f)) {
                Text("返回")
            }
            Button(onClick = onConfirm, modifier = Modifier.weight(1f)) {
                Text("确认同步")
            }
        }
    }
}

private const val MB = 1024 * 1024L
