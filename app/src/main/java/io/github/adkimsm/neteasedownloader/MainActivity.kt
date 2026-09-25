package io.github.adkimsm.neteasedownloader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.adkimsm.neteasedownloader.ui.Dest
import io.github.adkimsm.neteasedownloader.ui.DiagnosticsScreen
import io.github.adkimsm.neteasedownloader.ui.LoginScreen
import io.github.adkimsm.neteasedownloader.ui.LoginUiState
import io.github.adkimsm.neteasedownloader.ui.LoginViewModel
import io.github.adkimsm.neteasedownloader.ui.MainViewModel
import io.github.adkimsm.neteasedownloader.ui.MiniPlayerBar
import io.github.adkimsm.neteasedownloader.ui.NowPlayingScreen
import io.github.adkimsm.neteasedownloader.ui.PlayerViewModel
import io.github.adkimsm.neteasedownloader.ui.PlaylistDetailScreen
import io.github.adkimsm.neteasedownloader.ui.PlaylistDetailViewModel
import io.github.adkimsm.neteasedownloader.ui.PlaylistScreen
import io.github.adkimsm.neteasedownloader.ui.QueueScreen
import io.github.adkimsm.neteasedownloader.ui.DeleteResultBanner
import io.github.adkimsm.neteasedownloader.ui.AddToPlaylistScreen
import io.github.adkimsm.neteasedownloader.ui.LikedEntry
import io.github.adkimsm.neteasedownloader.ui.LikedSongsViewModel
import io.github.adkimsm.neteasedownloader.ui.PlaylistEditScreen
import io.github.adkimsm.neteasedownloader.ui.PlaylistMenuScreen
import io.github.adkimsm.neteasedownloader.ui.RemoveSongSheet
import io.github.adkimsm.neteasedownloader.ui.SettingsScreen
import io.github.adkimsm.neteasedownloader.ui.SongActionsScreen
import io.github.adkimsm.neteasedownloader.ui.SyncPreviewScreen
import io.github.adkimsm.neteasedownloader.ui.SyncProgressScreen
import io.github.adkimsm.neteasedownloader.ui.theme.LocalWindowSizing
import io.github.adkimsm.neteasedownloader.ui.theme.NeteaseDownloaderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NeteaseDownloaderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppNavigation()
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // 退到后台时把播放位置落盘一次,别只依赖 10s 的轮询间隔
        (application as App).playbackRepository.persistNow()
    }
}

@Composable
private fun AppNavigation() {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val mainViewModel: MainViewModel = viewModel()
    val playerViewModel: PlayerViewModel = viewModel()

    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val stack by mainViewModel.stack.collectAsStateWithLifecycle()
    val playerState by playerViewModel.state.collectAsStateWithLifecycle()
    val playingSong by playerViewModel.song.collectAsStateWithLifecycle()
    val deleteState by mainViewModel.delete.collectAsStateWithLifecycle()
    val sizing = LocalWindowSizing.current

    // 系统返回:栈非空就弹栈,而不是直接退出 App
    BackHandler(enabled = stack.isNotEmpty()) { mainViewModel.pop() }

    // Android 13+ 需要通知权限才能显示同步/播放通知
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

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            when (val dest = uiState.dest) {
                Dest.Login -> {
                    val loginViewModel: LoginViewModel = viewModel()
                    val state by loginViewModel.stateFlow.collectAsStateWithLifecycle()
                    LaunchedEffect(Unit) {
                        if (state is LoginUiState.Loading) loginViewModel.startLogin()
                    }
                    LoginScreen(state = state, onRefresh = loginViewModel::startLogin)
                }

                Dest.Playlists -> {
                    val playlists by mainViewModel.playlists.collectAsStateWithLifecycle()
                    val progress by mainViewModel.progress.collectAsStateWithLifecycle()
                    val loading by mainViewModel.playlistsLoading.collectAsStateWithLifecycle()
                    val pendingToggles by mainViewModel.pendingToggleIds.collectAsStateWithLifecycle()
                    val errorMessage by mainViewModel.errorMessage.collectAsStateWithLifecycle()
                    val likedCount by mainViewModel.likedCount.collectAsStateWithLifecycle()
                    PlaylistScreen(
                        playlists = playlists,
                        // FAILED 不在这里显示为"同步中":失败走下面的错误条
                        syncing = progress.stage in MainViewModel.ACTIVE_STAGES,
                        onToggle = mainViewModel::togglePlaylist,
                        onSyncClick = mainViewModel::startSync,
                        onSettingsClick = mainViewModel::openSettings,
                        onOpenPlaylist = mainViewModel::openPlaylistDetail,
                        likedEntry = LikedEntry(likedCount),
                        onOpenLiked = mainViewModel::openLikedSongs,
                        onCreatePlaylist = mainViewModel::openPlaylistCreate,
                        loading = loading,
                        pendingToggleIds = pendingToggles,
                        errorMessage = errorMessage,
                        onDismissError = mainViewModel::clearError,
                    )
                }

                Dest.Preview -> {
                    // 差量与 dest 同源产出:进到这一分支就一定有 diff
                    uiState.diff?.let { diff ->
                        SyncPreviewScreen(
                            diff = diff,
                            onConfirm = mainViewModel::confirmSync,
                            onDiscard = mainViewModel::discardPreview,
                        )
                    }
                }

                Dest.Syncing -> {
                    val progress by mainViewModel.progress.collectAsStateWithLifecycle()
                    SyncProgressScreen(progress = progress, onStop = mainViewModel::stopSync)
                }

                Dest.Settings -> {
                    val level by mainViewModel.level.collectAsStateWithLifecycle()
                    val streamLevel by mainViewModel.streamLevel.collectAsStateWithLifecycle()
                    val actionInFlight by mainViewModel.actionInFlight.collectAsStateWithLifecycle()
                    val levelChanged by mainViewModel.levelJustChanged.collectAsStateWithLifecycle()
                    val playlists by mainViewModel.playlists.collectAsStateWithLifecycle()
                    val removeScope by mainViewModel.removeScope.collectAsStateWithLifecycle()
                    val unlockDownload by mainViewModel.unlockDownload.collectAsStateWithLifecycle()
                    val unlockStream by mainViewModel.unlockStream.collectAsStateWithLifecycle()
                    val providerKuwo by mainViewModel.providerKuwo.collectAsStateWithLifecycle()
                    val providerKugou by mainViewModel.providerKugou.collectAsStateWithLifecycle()
                    val spoofRealIp by mainViewModel.spoofRealIp.collectAsStateWithLifecycle()
                    SettingsScreen(
                        currentLevel = level,
                        currentStreamLevel = streamLevel,
                        onLevelChange = mainViewModel::setLevel,
                        onStreamLevelChange = mainViewModel::setStreamLevel,
                        removeScope = removeScope,
                        onRemoveScopeChange = mainViewModel::setRemoveScope,
                        onBack = mainViewModel::pop,
                        onLogout = mainViewModel::logout,
                        onDiagnostics = mainViewModel::openDiagnostics,
                        onSyncClick = mainViewModel::startSync,
                        loggingOut = actionInFlight == MainViewModel.ACTION_LOGOUT,
                        levelJustChanged = levelChanged,
                        playlistCount = playlists.size,
                        enabledPlaylistCount = playlists.count { it.enabled },
                        unlockDownload = unlockDownload,
                        onUnlockDownloadChange = mainViewModel::setUnlockDownload,
                        unlockStream = unlockStream,
                        onUnlockStreamChange = mainViewModel::setUnlockStream,
                        providerKuwo = providerKuwo,
                        onProviderKuwoChange = mainViewModel::setProviderKuwo,
                        providerKugou = providerKugou,
                        onProviderKugouChange = mainViewModel::setProviderKugou,
                        spoofRealIp = spoofRealIp,
                        onSpoofRealIpChange = mainViewModel::setSpoofRealIp,
                    )
                }

                Dest.Diagnostics -> {
                    val logs by io.github.adkimsm.neteasedownloader.diag.Diag.logs.collectAsStateWithLifecycle()
                    DiagnosticsScreen(
                        logs = logs,
                        logFilePath = io.github.adkimsm.neteasedownloader.diag.Diag.logFilePath(),
                        onBack = mainViewModel::pop,
                        onClear = mainViewModel::clearDiagnostics,
                    )
                }

                is Dest.PlaylistDetail -> {
                    val detailViewModel: PlaylistDetailViewModel = viewModel(
                        key = "playlist-detail-${dest.playlistId}",
                        factory = PlaylistDetailViewModel.factory(dest.playlistId),
                    )
                    val tracks by detailViewModel.tracks.collectAsStateWithLifecycle()
                    val playlist by detailViewModel.playlist.collectAsStateWithLifecycle()
                    val loading by detailViewModel.loading.collectAsStateWithLifecycle()
                    val error by detailViewModel.error.collectAsStateWithLifecycle()
                    val libraryVersion by mainViewModel.libraryVersion.collectAsStateWithLifecycle()
                    // 播放开关:开着时 MISSING_URL 的歌在列表里仍可点
                    val streamFallback by mainViewModel.unlockStream.collectAsStateWithLifecycle()
                    // 删完之后列表要立刻少一首 —— 只读本地缓存,不重新拉网络
                    LaunchedEffect(libraryVersion) {
                        if (libraryVersion > 0) detailViewModel.refreshFromCache()
                    }
                    PlaylistDetailScreen(
                        title = playlist?.name
                            ?: stringResource(R.string.playlist_title),
                        tracks = tracks,
                        loading = loading,
                        error = error,
                        pendingSongIds = emptySet(),
                        streamFallback = streamFallback,
                        onBack = mainViewModel::pop,
                        onRetry = detailViewModel::load,
                        onDismissError = detailViewModel::clearError,
                        onPlayTrack = { index ->
                            playerViewModel.playList(tracks, index, dest.playlistId)
                        },
                        onTrackActions = mainViewModel::openSongActions,
                    )
                }

                Dest.NowPlaying -> {
                    NowPlayingScreen(
                        state = playerState,
                        song = playingSong,
                        onBack = mainViewModel::pop,
                        onMore = {
                            playerState.songId?.let(mainViewModel::openSongActions)
                                ?: mainViewModel.openQueue()
                        },
                        onTogglePlayPause = playerViewModel::togglePlayPause,
                        onSkipNext = playerViewModel::skipNext,
                        onSkipPrevious = playerViewModel::skipPrevious,
                        onSeek = playerViewModel::seekTo,
                        onDismissError = playerViewModel::clearError,
                    )
                }

                Dest.Queue -> {
                    val queueSongs by playerViewModel.queueSongs.collectAsStateWithLifecycle()
                    QueueScreen(
                        songs = queueSongs,
                        currentIndex = playerState.queueIndex,
                        onBack = mainViewModel::pop,
                        onPlayAt = playerViewModel::playAt,
                        onRemove = playerViewModel::removeFromQueue,
                    )
                }

                is Dest.RemoveSong -> {
                    RemoveSongSheet(
                        presence = deleteState.presence,
                        loading = deleteState.loading,
                        inFlight = deleteState.inFlight,
                        selection = deleteState.selection,
                        onSelectionChange = mainViewModel::updateSelection,
                        onConfirm = mainViewModel::confirmRemove,
                        onBack = mainViewModel::pop,
                    )
                }

                Dest.LikedSongs -> {
                    val likedViewModel: LikedSongsViewModel = viewModel()
                    val tracks by likedViewModel.tracks.collectAsStateWithLifecycle()
                    val loading by likedViewModel.loading.collectAsStateWithLifecycle()
                    val error by likedViewModel.error.collectAsStateWithLifecycle()
                    val libraryVersion by mainViewModel.libraryVersion.collectAsStateWithLifecycle()
                    val streamFallback by mainViewModel.unlockStream.collectAsStateWithLifecycle()
                    LaunchedEffect(libraryVersion) {
                        if (libraryVersion > 0) likedViewModel.refreshFromCache()
                    }
                    // 喜欢页拉完/失败后,回歌单页时「我喜欢的音乐」行的数量要同步
                    LaunchedEffect(loading) {
                        if (!loading) mainViewModel.refreshLikes()
                    }
                    PlaylistDetailScreen(
                        title = stringResource(R.string.playlist_liked_title),
                        tracks = tracks,
                        loading = loading,
                        error = error,
                        pendingSongIds = emptySet(),
                        streamFallback = streamFallback,
                        onBack = mainViewModel::pop,
                        onRetry = likedViewModel::load,
                        onDismissError = likedViewModel::clearError,
                        onPlayTrack = { index -> playerViewModel.playList(tracks, index, null) },
                        onTrackActions = mainViewModel::openSongActions,
                    )
                }

                is Dest.PlaylistMenu -> {
                    val playlistName by produceState(initialValue = "", dest.playlistId) {
                        value = app.playlistDao.getAll().firstOrNull { it.id == dest.playlistId }?.name.orEmpty()
                    }
                    PlaylistMenuScreen(
                        playlistName = playlistName,
                        onBack = mainViewModel::pop,
                        onRename = { mainViewModel.openPlaylistEdit(dest.playlistId) },
                        onDelete = {
                            mainViewModel.deletePlaylist(dest.playlistId) { error ->
                                if (error == null) {
                                    // 删完回两层:菜单 -> 歌单详情 -> 列表
                                    mainViewModel.pop()
                                    mainViewModel.pop()
                                }
                            }
                        },
                    )
                }

                is Dest.PlaylistEdit -> {
                    val playlistName by produceState(initialValue = "", dest.playlistId) {
                        value = if (dest.playlistId == null) {
                            ""
                        } else {
                            app.playlistDao.getAll().firstOrNull { it.id == dest.playlistId }?.name.orEmpty()
                        }
                    }
                    val remoteAction by mainViewModel.remoteAction.collectAsStateWithLifecycle()
                    var remoteError by remember { mutableStateOf<String?>(null) }
                    PlaylistEditScreen(
                        title = stringResource(
                            if (dest.playlistId == null) R.string.playlist_create else R.string.playlist_rename,
                        ),
                        initialName = playlistName,
                        submitting = remoteAction == MainViewModel.ACTION_REMOTE,
                        remoteError = remoteError,
                        onBack = {
                            remoteError = null
                            mainViewModel.pop()
                        },
                        onSubmit = { name ->
                            remoteError = null
                            val done: (String?) -> Unit = { error ->
                                if (error == null) mainViewModel.pop() else remoteError = error
                            }
                            if (dest.playlistId == null) {
                                mainViewModel.createPlaylist(name, done)
                            } else {
                                mainViewModel.renamePlaylist(dest.playlistId, name, done)
                            }
                        },
                    )
                }

                is Dest.AddToPlaylist -> {
                    val targets by mainViewModel.addTargets.collectAsStateWithLifecycle()
                    val remoteAction by mainViewModel.remoteAction.collectAsStateWithLifecycle()
                    val songName by produceState(initialValue = "", dest.songId) {
                        value = app.songDao.getByIds(listOf(dest.songId)).firstOrNull()?.name.orEmpty()
                    }
                    LaunchedEffect(Unit) { mainViewModel.loadAddTargets() }
                    AddToPlaylistScreen(
                        songName = songName,
                        targets = targets,
                        submitting = remoteAction == MainViewModel.ACTION_REMOTE,
                        onBack = mainViewModel::pop,
                        onConfirm = { ids ->
                            mainViewModel.addSongToPlaylists(dest.songId, ids) { error ->
                                if (error == null) mainViewModel.pop()
                                else mainViewModel.showRemoteError(error)
                            }
                        },
                    )
                }

                is Dest.SongActions -> {
                    // 二级菜单可能从一个不在播放的歌进入,标题得单独查一次
                    val title by produceState(initialValue = "", dest.songId) {
                        value = app.songDao.getByIds(listOf(dest.songId))
                            .firstOrNull()?.name.orEmpty()
                    }
                    val likedIds by mainViewModel.likedIds.collectAsStateWithLifecycle()
                    SongActionsScreen(
                        songTitle = title.ifEmpty { stringResource(R.string.player_nothing) },
                        liked = dest.songId in likedIds,
                        onToggleLike = { mainViewModel.toggleLike(dest.songId) },
                        onAddToPlaylist = { mainViewModel.openAddToPlaylist(dest.songId) },
                        hasPlayer = playerState.songId != null,
                        currentRepeat = playerState.repeat,
                        shuffle = playerState.shuffle,
                        onBack = mainViewModel::pop,
                        onDelete = { mainViewModel.startRemove(dest.songId) },
                        onOpenQueue = mainViewModel::openQueue,
                        onCycleRepeat = playerViewModel::cycleRepeat,
                        onToggleShuffle = playerViewModel::toggleShuffle,
                    )
                }
            }
        }

        deleteState.banner?.let { banner ->
            DeleteResultBanner(
                songName = banner.songName,
                outcome = banner.outcome,
                report = banner.report,
                onUndo = mainViewModel::undoRemove,
                onRetry = mainViewModel::retryRemove,
                onDismiss = mainViewModel::dismissDeleteBanner,
                modifier = Modifier.padding(
                    start = sizing.screenPadding,
                    end = sizing.screenPadding,
                    bottom = sizing.gapSm,
                ),
            )
        }

        if (uiState.showMiniPlayer) {
            MiniPlayerBar(
                song = playingSong,
                isPlaying = playerState.isPlaying,
                onClick = mainViewModel::openNowPlaying,
                onTogglePlayPause = playerViewModel::togglePlayPause,
                // 有已知时长才显示进度线;未知时长(加载中/损坏)不画
                progressFraction = if (playerState.durationMs > 0L) {
                    (playerState.positionMs.toFloat() / playerState.durationMs).coerceIn(0f, 1f)
                } else {
                    null
                },
                modifier = Modifier.padding(
                    start = sizing.screenPadding,
                    end = sizing.screenPadding,
                    bottom = sizing.screenPadding,
                ),
            )
        }
    }
}
