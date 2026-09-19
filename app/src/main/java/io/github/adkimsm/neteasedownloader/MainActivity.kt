package io.github.adkimsm.neteasedownloader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.adkimsm.neteasedownloader.ui.LoginScreen
import io.github.adkimsm.neteasedownloader.ui.LoginUiState
import io.github.adkimsm.neteasedownloader.ui.LoginViewModel
import io.github.adkimsm.neteasedownloader.ui.MainViewModel
import io.github.adkimsm.neteasedownloader.ui.PlaylistScreen
import io.github.adkimsm.neteasedownloader.ui.SettingsScreen
import io.github.adkimsm.neteasedownloader.ui.SyncPreviewScreen
import io.github.adkimsm.neteasedownloader.ui.SyncProgressScreen
import io.github.adkimsm.neteasedownloader.ui.DiagnosticsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            androidx.compose.material3.MaterialTheme {
                AppNavigation()
            }
        }
    }
}

@Composable
private fun AppNavigation() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val mainViewModel: MainViewModel = viewModel()
    val screen by mainViewModel.screen.collectAsStateWithLifecycle()

    // Android 13+ 需要通知权限才能显示同步通知
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    when (screen) {
        MainViewModel.Screen.LOGIN -> {
            val loginViewModel: LoginViewModel = viewModel()
            val state by loginViewModel.stateFlow.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) {
                if (state is LoginUiState.Loading) loginViewModel.startLogin()
            }
            LoginScreen(state = state, onRefresh = loginViewModel::startLogin)
        }

        MainViewModel.Screen.PLAYLISTS -> {
            val playlists by mainViewModel.playlists.collectAsStateWithLifecycle()
            val progress by mainViewModel.progress.collectAsState()
            PlaylistScreen(
                playlists = playlists,
                syncing = progress.stage in setOf(
                    io.github.adkimsm.neteasedownloader.sync.SyncEngine.Stage.REFRESHING,
                    io.github.adkimsm.neteasedownloader.sync.SyncEngine.Stage.DOWNLOADING,
                    io.github.adkimsm.neteasedownloader.sync.SyncEngine.Stage.DELETING,
                ),
                onToggle = mainViewModel::togglePlaylist,
                onSyncClick = mainViewModel::startSync,
                onSettingsClick = mainViewModel::openSettings,
            )
        }

        MainViewModel.Screen.PREVIEW -> {
            mainViewModel.lastDiff?.let { diff ->
                SyncPreviewScreen(
                    diff = diff,
                    onConfirm = mainViewModel::confirmSync,
                    onDiscard = mainViewModel::discardPreview,
                )
            }
        }

        MainViewModel.Screen.SYNCING -> {
            val progress by mainViewModel.progress.collectAsState()
            SyncProgressScreen(progress = progress, onStop = mainViewModel::stopSync)
        }

        MainViewModel.Screen.SETTINGS -> {
            val level by mainViewModel.level.collectAsState()
            SettingsScreen(
                currentLevel = level,
                onLevelChange = mainViewModel::setLevel,
                onBack = mainViewModel::closeSettings,
                onLogout = mainViewModel::logout,
                onDiagnostics = mainViewModel::openDiagnostics,
            )
        }

        MainViewModel.Screen.DIAGNOSTICS -> {
            val logs by io.github.adkimsm.neteasedownloader.diag.Diag.logs.collectAsState()
            DiagnosticsScreen(
                logs = logs,
                logFilePath = io.github.adkimsm.neteasedownloader.diag.Diag.logFilePath(),
                onBack = mainViewModel::closeDiagnostics,
                onClear = mainViewModel::clearDiagnostics,
            )
        }
    }
}
