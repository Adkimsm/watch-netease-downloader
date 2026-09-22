package io.github.adkimsm.neteasedownloader.player

import io.github.adkimsm.neteasedownloader.data.SongState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「本地优先 + 串流兜底」的决策表。
 *
 * 这是整条播放链路里唯一决定"到底播什么"的地方,所以每个分支都要钉死:
 * 本地优先只认「有 localUri 且 state == OK」;只有这样,文件被外部删掉时才
 * 会走到 ExoPlayer 报错 → 播放层清本地态 → 回退串流那条自动降级路径。
 */
class LocalFirstResolverTest {

    private val localFile = "content://media/external_primary/audio/media/42"

    private fun decide(
        localUri: String? = null,
        state: String = SongState.OK.name,
        hasNetwork: Boolean = true,
        streamUrl: String? = null,
    ) = decideTarget(localUri, state, hasNetwork, streamUrl)

    @Test
    fun downloadedSong_playsLocalEvenWithoutNetwork() {
        assertEquals(
            ResolveTarget.Local(localFile),
            decide(localUri = localFile, hasNetwork = false),
        )
    }

    @Test
    fun downloadedSong_prefersLocalOverStream() {
        assertEquals(
            "有本地文件时不该走网络",
            ResolveTarget.Local(localFile),
            decide(localUri = localFile, streamUrl = "https://cdn/a.mp3"),
        )
    }

    @Test
    fun pendingSong_withNetworkAndUrl_streams() {
        assertEquals(
            ResolveTarget.Stream("https://cdn/a.mp3"),
            decide(localUri = null, state = SongState.PENDING.name, streamUrl = "https://cdn/a.mp3"),
        )
    }

    @Test
    fun failedSong_withUsableUrl_stillStreams() {
        assertEquals(
            ResolveTarget.Stream("https://cdn/a.mp3"),
            decide(localUri = null, state = SongState.FAILED.name, streamUrl = "https://cdn/a.mp3"),
        )
    }

    @Test
    fun localUriWithNonOkState_isNotTrustedAsLocal() {
        // 例如歌曲被移出歌单后清了本地态:不能再按本地播
        assertEquals(
            ResolveTarget.Stream("https://cdn/a.mp3"),
            decide(localUri = localFile, state = SongState.PENDING.name, streamUrl = "https://cdn/a.mp3"),
        )
    }

    @Test
    fun blankLocalUri_fallsBackToStream() {
        assertEquals(
            ResolveTarget.Stream("https://cdn/a.mp3"),
            decide(localUri = "", state = SongState.OK.name, streamUrl = "https://cdn/a.mp3"),
        )
    }

    @Test
    fun noLocalAndNoNetwork_isUnavailable() {
        assertEquals(
            ResolveTarget.Unavailable,
            decide(localUri = null, hasNetwork = false, streamUrl = null),
        )
    }

    @Test
    fun noLocalAndNoNetwork_ignoresStreamUrl() {
        // 无版权歌曲即使本地缓存过地址,没网也播不了
        assertEquals(
            ResolveTarget.Unavailable,
            decide(localUri = null, hasNetwork = false, streamUrl = "https://cdn/a.mp3"),
        )
    }

    @Test
    fun noLocalAndNoStreamUrl_isUnavailable() {
        assertEquals(
            ResolveTarget.Unavailable,
            decide(localUri = null, hasNetwork = true, streamUrl = null),
        )
    }

    @Test
    fun noLocalAndBlankStreamUrl_isUnavailable() {
        assertEquals(
            ResolveTarget.Unavailable,
            decide(localUri = null, hasNetwork = true, streamUrl = ""),
        )
    }

    @Test
    fun emptyStateString_isNotLocal() {
        assertEquals(
            ResolveTarget.Unavailable,
            decide(localUri = localFile, state = "", hasNetwork = false),
        )
    }
}
