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
    fun realWatchCheck_372x430px_atDensity320_isCompact() {
        // 372×430 px @ density 320(2.0x)= 186×215 dp。
        // 这是手表上最可能的实际情况(小圆表把 px 密度拉得很高),落在 Compact。
        // 固定该换算,避免后人把 px 当 dp 直接比。
        assertEquals(WindowClass.Compact, windowClassFor(minOf(186, 215)))
    }

    @Test
    fun dpConversion_explainsWhyPxMustNotBeComparedDirectly() {
        // 同为 372 px 宽,密度不同则 dp 不同,档位也不同 ——
        // 故档位只能用 dp 判定。
        val atDensity160 = (372 * 160 / 160) // = 372 dp
        val atDensity320 = (372 * 160 / 320) // = 186 dp
        assertEquals(WindowClass.Expanded, windowClassFor(atDensity160))
        assertEquals(WindowClass.Compact, windowClassFor(atDensity320))
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

    @Test
    fun playerTokens_shrinkMonotonicallyWithWindow() {
        val compact = sizingFor(WindowClass.Compact)
        val medium = sizingFor(WindowClass.Medium)
        val expanded = sizingFor(WindowClass.Expanded)

        assertTrue(compact.playerControl <= medium.playerControl)
        assertTrue(medium.playerControl <= expanded.playerControl)

        assertTrue(compact.seekBarHeight <= medium.seekBarHeight)
        assertTrue(medium.seekBarHeight <= expanded.seekBarHeight)

        assertTrue(compact.menuRowHeight <= medium.menuRowHeight)
        assertTrue(medium.menuRowHeight <= expanded.menuRowHeight)

        assertTrue(compact.bannerMinHeight <= medium.bannerMinHeight)
        assertTrue(medium.bannerMinHeight <= expanded.bannerMinHeight)
    }

    @Test
    fun menuRowHeight_respectsTouchTargetFloor() {
        // 二级菜单行 / 曲目行也是点击目标,不得低于 36dp 下限
        WindowClass.entries.forEach { wc ->
            assertTrue(
                "menuRowHeight for $wc was ${sizingFor(wc).menuRowHeight}",
                sizingFor(wc).menuRowHeight.value >= 36f,
            )
        }
    }

    @Test
    fun seekBarHeight_respectsTouchTargetFloor() {
        // 进度条要能拖:拖动区域小于 36dp 在手表上基本拖不动
        WindowClass.entries.forEach { wc ->
            assertTrue(
                "seekBarHeight for $wc was ${sizingFor(wc).seekBarHeight}",
                sizingFor(wc).seekBarHeight.value >= 36f,
            )
        }
    }

    @Test
    fun playerControl_isAtLeastAsLargeAsAnyOtherTapTarget() {
        // 播放/暂停是全应用最重要的一个键,不该比别的可点元素还小
        WindowClass.entries.forEach { wc ->
            val sizing = sizingFor(wc)
            assertTrue(
                "playerControl for $wc was ${sizing.playerControl}",
                sizing.playerControl >= sizing.touchTarget,
            )
        }
    }

    @Test
    fun playerControl_stillFitsInCompactWindow() {
        // Compact 档竖向预算很紧:主控键不能大到把歌名与进度挤出去
        val compact = sizingFor(WindowClass.Compact)
        assertTrue(compact.playerControl.value <= 56f)
    }
}
