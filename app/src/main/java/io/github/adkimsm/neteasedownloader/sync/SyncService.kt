package io.github.adkimsm.neteasedownloader.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.adkimsm.neteasedownloader.App
import io.github.adkimsm.neteasedownloader.diag.Diag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 前台服务跑同步流程,息屏/后台不被杀;同步中显示进度通知。
 * 两段式:REFRESH(拉取+差量)→ 用户在 App 里确认 → EXECUTE(下载+删除)。
 */
class SyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var refreshJob: Job? = null
    private var executeJob: Job? = null

    private val engine get() = App.instance.syncEngine

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, baseNotification("准备同步…", 0, 0))
        observeProgress()
    }

    private fun observeProgress() {
        scope.launch {
            engine.progress.collectLatest { p ->
                val text = when (p.stage) {
                    SyncEngine.Stage.IDLE -> p.message.ifEmpty { "空闲" }
                    SyncEngine.Stage.REFRESHING -> "拉取中:${p.message}"
                    SyncEngine.Stage.READY -> p.message
                    SyncEngine.Stage.DOWNLOADING -> "下载 ${p.done}/${p.total}:${p.message}"
                    SyncEngine.Stage.TAGGING -> "填充歌曲信息:${p.message}"
                    SyncEngine.Stage.NORMALIZING -> "规范文件名:${p.message}"
                    SyncEngine.Stage.DELETING -> "清理:${p.message}"
                    SyncEngine.Stage.DONE -> "同步完成"
                    SyncEngine.Stage.FAILED -> "失败:${p.message}"
                }
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, baseNotification(text, p.total, p.done))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Diag.i("SyncService", "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_REFRESH -> startRefresh()
            ACTION_EXECUTE -> startExecute()
            ACTION_STOP -> {
                refreshJob?.cancel()
                executeJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> startRefresh()
        }
        return START_NOT_STICKY
    }

    private fun startRefresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            try {
                engine.lastDiff = engine.refreshAndDiff()
            } catch (e: kotlinx.coroutines.CancellationException) {
                Diag.i("SyncService", "刷新被取消")
                throw e
            } catch (e: Exception) {
                Diag.e("SyncService", "刷新失败:${e.message}", e)
                engine.emitError(e.message ?: "刷新失败")
            }
        }
    }

    private fun startExecute() {
        if (executeJob?.isActive == true) return
        executeJob = scope.launch {
            try {
                engine.lastDiff?.let { engine.execute(it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Diag.i("SyncService", "执行被取消")
                throw e
            } catch (e: Exception) {
                Diag.e("SyncService", "同步失败:${e.message}", e)
                engine.emitError(e.message ?: "同步失败")
            }
        }
    }

    override fun onDestroy() {
        refreshJob?.cancel()
        executeJob?.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "音乐同步",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun baseNotification(text: String, total: Int, done: Int): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("网易云音乐同步")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
        if (total > 0) {
            builder.setProgress(total, done, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "sync"
        private const val NOTIFICATION_ID = 1
        const val ACTION_REFRESH = "io.github.adkimsm.neteasedownloader.REFRESH_SYNC"
        const val ACTION_EXECUTE = "io.github.adkimsm.neteasedownloader.EXECUTE_SYNC"
        const val ACTION_STOP = "io.github.adkimsm.neteasedownloader.STOP_SYNC"

        fun refresh(context: Context) {
            startWithAction(context, ACTION_REFRESH)
        }

        fun execute(context: Context) {
            startWithAction(context, ACTION_EXECUTE)
        }

        fun stop(context: Context) {
            startWithAction(context, ACTION_STOP)
        }

        private fun startWithAction(context: Context, action: String) {
            val intent = Intent(context, SyncService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
