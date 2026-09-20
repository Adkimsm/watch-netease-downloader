package io.github.adkimsm.neteasedownloader.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 窗口档位与尺寸令牌的边界测试。
 *
 * 这些阈值决定整个 UI 的密度,越界一格就会导致小屏上按钮被挤出屏幕,
 * 所以边界值(299/300/359/360)逐个固定下来。
 */
class WindowSizingTest {

    @Test
    fun shortestSide_justBelow300_isCompact() {
        assertEquals(WindowClass.Compact, windowClassFor(299))
    }

    @Test
    fun shortestSide_at300_isMedium() {
        assertEquals(WindowClass.Medium, windowClassFor(300))
    }

    @Test
    fun shortestSide_justBelow360_isMedium() {
        assertEquals(WindowClass.Medium, windowClassFor(359))
    }

    @Test
    fun shortestSide_at360_isExpanded() {
        assertEquals(WindowClass.Expanded, windowClassFor(360))
    }

    @Test
    fun mainWatchTarget_372_isExpanded() {
        // 主力机型 372×430:短边 372 ≥ 360,落在 Expanded(尺寸最宽松)。
        // 固定该事实,避免后人误改阈值把主力机型的密度改坏。
        assertEquals(WindowClass.Expanded, windowClassFor(372))
    }

    @Test
    fun squareSmallWatch_320_isMedium() {
        assertEquals(WindowClass.Medium, windowClassFor(320))
    }

    @Test
    fun touchTarget_neverBelowComfortableMinimum() {
        // 触控目标逐档收缩,但任何档位都不得低于 36dp,否则手表上难以点中
        WindowClass.entries.forEach { wc ->
            val sizing = sizingFor(wc)
            assertTrue(
                "touchTarget for $wc was ${sizing.touchTarget}",
                sizing.touchTarget.value >= 36f,
            )
        }
    }

    @Test
    fun sizing_shrinksMonotonicallyWithWindow() {
        val compact = sizingFor(WindowClass.Compact)
        val medium = sizingFor(WindowClass.Medium)
        val expanded = sizingFor(WindowClass.Expanded)

        // 窗口越大,页面内边距、按钮、封面、进度条都不得变小
        assertTrue(compact.screenPadding <= medium.screenPadding)
        assertTrue(medium.screenPadding <= expanded.screenPadding)

        assertTrue(compact.primaryButtonHeight <= medium.primaryButtonHeight)
        assertTrue(medium.primaryButtonHeight <= expanded.primaryButtonHeight)

        assertTrue(compact.thumbSize <= medium.thumbSize)
        assertTrue(medium.thumbSize <= expanded.thumbSize)

        assertTrue(compact.progressBar <= medium.progressBar)
        assertTrue(medium.progressBar <= expanded.progressBar)
    }

    @Test
    fun expandedSizing_matchesLegacyDimens() {
        // Expanded 档位刻意沿用改造前的 Dimens,保证大屏外观不回归
        val expanded = sizingFor(WindowClass.Expanded)
        assertEquals(Dimens.ScreenPadding, expanded.screenPadding)
        assertEquals(Dimens.TouchTarget, expanded.touchTarget)
        assertEquals(Dimens.PrimaryButton, expanded.primaryButtonHeight)
        assertEquals(Dimens.ThumbSize, expanded.thumbSize)
        assertEquals(Dimens.ProgressBar, expanded.progressBar)
    }

    @Test
    fun compactSizing_isTighterThanLegacyDimensOnEveryAxis() {
        // Compact 必须在每个轴上都比旧的固定值更省空间,否则小屏适配没有意义
        val compact = sizingFor(WindowClass.Compact)
        assertTrue(compact.screenPadding < Dimens.ScreenPadding)
        assertTrue(compact.touchTarget < Dimens.TouchTarget)
        assertTrue(compact.primaryButtonHeight < Dimens.PrimaryButton)
        assertTrue(compact.thumbSize < Dimens.ThumbSize)
    }

    @Test
    fun progressPercentFont_fitsInSmallestWindow() {
        // 大号百分比是进度页最宽的单个元素,24sp 在 300dp 宽的小表上仍应留有余量
        val compact = sizingFor(WindowClass.Compact)
        assertTrue(compact.progressPercentSp <= 28)
    }
}
