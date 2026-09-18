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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.adkimsm.neteasedownloader.data.SettingsStore

@Composable
fun SettingsScreen(
    currentLevel: String,
    onLevelChange: (String) -> Unit,
    onBack: () -> Unit,
    onLogout: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Text("设置", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("音质", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingsStore.LEVELS.forEach { level ->
                FilterChip(
                    selected = level == currentLevel,
                    onClick = { onLevelChange(level) },
                    label = { Text(levelText(level)) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("返回")
        }
        Spacer(Modifier.height(4.dp))
        Button(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
            Text("退出登录")
        }
    }
}

private fun levelText(level: String): String = when (level) {
    SettingsStore.LEVEL_STANDARD -> "标准"
    SettingsStore.LEVEL_HIGHER -> "较高"
    SettingsStore.LEVEL_EXHIGH -> "极高"
    SettingsStore.LEVEL_LOSSLESS -> "无损"
    else -> level
}
