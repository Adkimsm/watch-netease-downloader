package io.github.adkimsm.neteasedownloader.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun LoginScreen(state: LoginUiState, onRefresh: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when (state) {
            is LoginUiState.Loading -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(8.dp))
                Text("正在生成二维码…", style = MaterialTheme.typography.bodySmall)
            }

            is LoginUiState.Waiting -> Row(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = androidx.compose.ui.graphics.Color.White,
                ) {
                    Image(
                        painter = BitmapPainter(state.qrBitmap.asImageBitmap()),
                        contentDescription = "网易云登录二维码",
                        modifier = Modifier.size(150.dp).padding(4.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "网易云App扫码登录",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Start,
                    )
                }
            }

            is LoginUiState.Success -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "登录成功",
                    style = MaterialTheme.typography.titleMedium,
                )
                state.account?.nickname?.let { nickname ->
                    Spacer(Modifier.height(4.dp))
                    Text(nickname, style = MaterialTheme.typography.bodySmall)
                }
            }

            is LoginUiState.Error -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onRefresh) { Text("重试") }
            }
        }
    }
}
