package io.github.adkimsm.neteasedownloader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import io.github.adkimsm.neteasedownloader.ui.LoginScreen
import io.github.adkimsm.neteasedownloader.ui.LoginUiState
import io.github.adkimsm.neteasedownloader.ui.LoginViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as App
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val musicU by app.cookieStore.musicUState.collectAsStateWithLifecycle()
                    if (musicU.isEmpty()) {
                        val viewModel: LoginViewModel = viewModel()
                        val state by viewModel.stateFlow.collectAsStateWithLifecycle()
                        LaunchedEffect(Unit) {
                            if (state is LoginUiState.Loading) viewModel.startLogin()
                        }
                        LoginScreen(state = state, onRefresh = viewModel::startLogin)
                    } else {
                        LoggedInPlaceholder(onLogout = {
                            app.appScope.launch { app.cookieStore.clear() }
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun LoggedInPlaceholder(onLogout: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("已登录", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onLogout) { Text("退出登录") }
    }
}
