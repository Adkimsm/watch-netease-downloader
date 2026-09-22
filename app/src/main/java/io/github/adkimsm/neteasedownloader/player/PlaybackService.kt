package io.github.adkimsm.neteasedownloader.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.adkimsm.neteasedownloader.App
import io.github.adkimsm.neteasedownloader.MainActivity
import io.github.adkimsm.neteasedownloader.R
import io.github.adkimsm.neteasedownloader.diag.Diag

/**
 * 播放前台服务:持有 ExoPlayer 与 MediaSession,负责后台/息屏播放与媒体通知。
 *
 * 几点取舍:
 *  - 音频焦点、拔耳机自动暂停、WakeMode(NETWORK)全部交给 ExoPlayer,不自己写;
 *  - 通知用 media3 的 [DefaultMediaNotificationProvider](只换 channel 与 id),
 *    自己写 Provider 的收益不抵风险;装机后若排版不佳再换;
 *  - **不设置 artworkUri**:本地文件没有专辑图,拉一个对不上的远端封面只会更糟;
 *  - `onTaskRemoved` 用父类默认实现(未在播放时自动停服务)。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as App

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(app.playbackMediaSourceFactory())
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player = exo

        mediaSession = MediaSession.Builder(this, exo)
            .setSessionActivity(openAppIntent())
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.playback_channel_name)
                .setNotificationId(NOTIFICATION_ID)
                .build(),
        )
        Diag.i(TAG, "PlaybackService 已创建")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        Diag.i(TAG, "PlaybackService 销毁")
        // 注意:在 mediaSession.run { } 里写 player 会解析到 MediaSession.getPlayer(),
        // 虽然此刻恰好是同一个实例,但语义含糊 —— 先把两个引用取成局部变量再释放。
        val session = mediaSession
        val exo = player
        mediaSession = null
        player = null
        exo?.release()
        session?.release()
        super.onDestroy()
    }

    /** 点媒体通知回到 App(播放页由路由决定,这里只负责把界面拉起来) */
    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        REQUEST_CODE,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "PlaybackService"
        private const val REQUEST_CODE = 100

        /** 与 SyncService 的 "sync"/notificationId=1 分开,两个前台服务可并存 */
        const val CHANNEL_ID = "playback"
        const val NOTIFICATION_ID = 2
    }
}
