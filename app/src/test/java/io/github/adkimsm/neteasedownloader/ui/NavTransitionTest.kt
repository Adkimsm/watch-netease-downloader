package io.github.adkimsm.neteasedownloader.ui

import io.github.adkimsm.neteasedownloader.ui.theme.Motion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面过渡动画的规则测试。
 *
 * 动画本身没法在 JVM 单测里断言,但「哪类页面之间用什么过渡、朝哪个方向」是一条**纯规则**,
 * 它决定了用户看到的是「推进」还是「退回」。把这条规则抽成 [layerOf] / [navTransitionFor] /
 * [slidesForward] 三个纯函数并用本用例钉住,与 [routeDest] 的做法一致 ——
 * 否则这条规则只能靠反复上手表试,改一处就可能悄悄把方向弄反。
 *
 * 特别注意 [sameTypeDifferentArgIsStillForward]:`PlaylistDetail(1) -> PlaylistDetail(2)`
 * 这类跳转如果靠「dest 是否相等」判方向,会被误判成「没变」而退化成淡入淡出。
 */
class NavTransitionTest {

    // ---- layerOf:根层面 vs 入栈层面 ----

    @Test
    fun rootDestinationsAreRootLayer() {
        assertEquals(DestLayer.Root, layerOf(Dest.Login))
        assertEquals(DestLayer.Root, layerOf(Dest.Playlists))
    }

    @Test
    fun modalDestinationsAreRootLayer() {
        // 同步预览/进度是模态:由流程触发、结束回到发起页,不参与横向推进
        assertEquals(DestLayer.Root, layerOf(Dest.Preview))
        assertEquals(DestLayer.Root, layerOf(Dest.Syncing))
    }

    @Test
    fun pushedDestinationsAreStackedLayer() {
        assertEquals(DestLayer.Stacked, layerOf(Dest.NowPlaying))
        assertEquals(DestLayer.Stacked, layerOf(Dest.Queue))
        assertEquals(DestLayer.Stacked, layerOf(Dest.Settings))
        assertEquals(DestLayer.Stacked, layerOf(Dest.Diagnostics))
        assertEquals(DestLayer.Stacked, layerOf(Dest.LikedSongs))
        assertEquals(DestLayer.Stacked, layerOf(Dest.PlaylistDetail(1L)))
        assertEquals(DestLayer.Stacked, layerOf(Dest.SongActions(1L)))
        assertEquals(DestLayer.Stacked, layerOf(Dest.RemoveSong(1L)))
        assertEquals(DestLayer.Stacked, layerOf(Dest.PlaylistMenu(1L)))
        assertEquals(DestLayer.Stacked, layerOf(Dest.PlaylistEdit(null)))
        assertEquals(DestLayer.Stacked, layerOf(Dest.AddToPlaylist(1L)))
    }

    // ---- navTransitionFor:过渡形态 ----

    @Test
    fun rootToRootIsFade() {
        assertEquals(NavTransition.Fade, navTransitionFor(Dest.Login, Dest.Playlists))
        assertEquals(NavTransition.Fade, navTransitionFor(Dest.Playlists, Dest.Login))
    }

    @Test
    fun modalsFadeInAndOut() {
        // 进模态 / 出模态都该是淡入淡出:横滑会让人以为「进了新页面且回不去了」
        assertEquals(NavTransition.Fade, navTransitionFor(Dest.Playlists, Dest.Preview))
        assertEquals(NavTransition.Fade, navTransitionFor(Dest.Preview, Dest.Playlists))
        assertEquals(NavTransition.Fade, navTransitionFor(Dest.Playlists, Dest.Syncing))
        assertEquals(NavTransition.Fade, navTransitionFor(Dest.Syncing, Dest.Playlists))
    }

    @Test
    fun enteringAndLeavingStackSlides() {
        assertEquals(NavTransition.Slide, navTransitionFor(Dest.Playlists, Dest.NowPlaying))
        assertEquals(NavTransition.Slide, navTransitionFor(Dest.NowPlaying, Dest.Playlists))
        assertEquals(NavTransition.Slide, navTransitionFor(Dest.Playlists, Dest.Settings))
    }

    @Test
    fun stackedToStackedSlides() {
        assertEquals(
            NavTransition.Slide,
            navTransitionFor(Dest.PlaylistDetail(1L), Dest.SongActions(2L)),
        )
        assertEquals(
            NavTransition.Slide,
            navTransitionFor(Dest.SongActions(2L), Dest.PlaylistEdit(null)),
        )
    }

    @Test
    fun firstFrameIsFade() {
        // 首帧没有「上一个页面」可比,一律淡入 —— 不能凭空横滑一下
        assertEquals(NavTransition.Fade, navTransitionFor(null, Dest.Playlists))
        assertEquals(NavTransition.Fade, navTransitionFor(null, Dest.NowPlaying))
    }

    // ---- slidesForward:方向 ----

    @Test
    fun rootToStackedSlidesForward() {
        assertTrue(slidesForward(Dest.Playlists, Dest.PlaylistDetail(1L)))
        assertTrue(slidesForward(Dest.Playlists, Dest.NowPlaying))
    }

    @Test
    fun stackedToRootSlidesBackward() {
        assertFalse(slidesForward(Dest.PlaylistDetail(1L), Dest.Playlists))
        assertFalse(slidesForward(Dest.Settings, Dest.Playlists))
        // 模态(Preview/Syncing)进出走的是 Fade,方向值在那条路径上根本不会被读 ——
        // 所以这里只断言「根层面之间不会横滑」,不去规定一个用不到的返回值。
        assertEquals(NavTransition.Fade, navTransitionFor(Dest.Preview, Dest.Playlists))
    }

    @Test
    fun stackedToStackedKeepsGoingForward() {
        // 详情 -> 二级菜单 -> 编辑:整条链都该是「向右推进」,返回时逐层反向
        assertTrue(slidesForward(Dest.PlaylistDetail(1L), Dest.SongActions(2L)))
        assertTrue(slidesForward(Dest.SongActions(2L), Dest.AddToPlaylist(3L)))
    }

    @Test
    fun sameTypeDifferentArgIsStillForward() {
        // 靠比较 dest 是否相等会在这里退化成「没变」:锚点不同就是一次新的推进
        assertTrue(slidesForward(Dest.PlaylistDetail(1L), Dest.PlaylistDetail(2L)))
        assertEquals(
            NavTransition.Slide,
            navTransitionFor(Dest.PlaylistDetail(1L), Dest.PlaylistDetail(2L)),
        )
    }

    // ---- 列表内容形态(骨架屏 -> 列表) ----

    @Test
    fun loadingWithNoDataShowsSkeleton() {
        // 骨架屏优先于空态:否则首次进入会先闪一下「没有歌曲/没有歌单」
        assertEquals(ListContentState.Loading, listContentState(loading = true, isEmpty = true))
    }

    @Test
    fun loadedAndStillEmptyShowsEmptyState() {
        assertEquals(ListContentState.Empty, listContentState(loading = false, isEmpty = true))
    }

    @Test
    fun hasDataShowsContent() {
        // 有数据时即便还在刷新,也不该把列表换成骨架屏
        assertEquals(ListContentState.Content, listContentState(loading = false, isEmpty = false))
        assertEquals(ListContentState.Content, listContentState(loading = true, isEmpty = false))
    }

    // ---- 时长上限:手表上不能拖太久 ----

    @Test
    fun durationsStayShortEnoughForAWrist() {
        // Material 默认 300~400ms 是给手机定的;手表位移距离短,拖久了会显得"发飘"
        assertTrue(Motion.NavDurationMs <= 300)
        assertTrue(Motion.OverlayDurationMs <= Motion.NavDurationMs)
        assertTrue(Motion.ContentDurationMs <= Motion.NavDurationMs)
        assertTrue(Motion.NavSlideDistanceDp > 0)
    }
}
