package io.github.adkimsm.neteasedownloader.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手动切歌与播放模式的纯逻辑。
 *
 * 这些用例钉住的是 ExoPlayer 语义与"手表上的预期"不一致的两处:
 *  - 单曲循环下点"下一首"必须前进,而不是重播当前曲(需要临时绕开 REPEAT_MODE_ONE);
 *  - 末尾 + 非列表循环时"下一首"无处可去,按钮该置灰而不是静默重播。
 */
class PlaybackQueueTest {

    @Test
    fun nextRepeat_cyclesOffAllOne() {
        assertEquals(Repeat.ALL, nextRepeat(Repeat.OFF))
        assertEquals(Repeat.ONE, nextRepeat(Repeat.ALL))
        assertEquals(Repeat.OFF, nextRepeat(Repeat.ONE))
    }

    @Test
    fun nextRepeat_returnsToStartAfterThreeSteps() {
        var mode = Repeat.OFF
        repeat(3) { mode = nextRepeat(mode) }
        assertEquals(Repeat.OFF, mode)
    }

    @Test
    fun repeatBypass_neededOnlyForSingleRepeat() {
        assertTrue(needsRepeatBypassForManualSkip(Repeat.ONE))
        assertFalse(needsRepeatBypassForManualSkip(Repeat.OFF))
        assertFalse(needsRepeatBypassForManualSkip(Repeat.ALL))
    }

    @Test
    fun nextSkip_advancesWithinQueue() {
        assertEquals(3, nextSkipIndex(2, 5, Repeat.OFF))
        assertEquals(3, nextSkipIndex(2, 5, Repeat.ALL))
        assertEquals(3, nextSkipIndex(2, 5, Repeat.ONE))
    }

    @Test
    fun nextSkip_atEndWithoutListRepeat_hasNoTarget() {
        assertNull("末尾 + 顺序播放不该有下一首", nextSkipIndex(4, 5, Repeat.OFF))
        assertNull("单曲循环同样到不了下一位", nextSkipIndex(4, 5, Repeat.ONE))
    }

    @Test
    fun nextSkip_atEndWithListRepeat_wrapsToFirst() {
        assertEquals(0, nextSkipIndex(4, 5, Repeat.ALL))
    }

    @Test
    fun nextSkip_singleItemQueue_onlyWrapsWithListRepeat() {
        assertNull(nextSkipIndex(0, 1, Repeat.OFF))
        assertEquals(0, nextSkipIndex(0, 1, Repeat.ALL))
    }

    @Test
    fun nextSkip_emptyOrInvalidQueue_hasNoTarget() {
        assertNull(nextSkipIndex(0, 0, Repeat.ALL))
        assertNull(nextSkipIndex(0, -1, Repeat.ALL))
    }

    @Test
    fun nextSkip_outOfRangeIndex_isClamped() {
        // 索引越界(队列被删短了)不能抛异常,按末位处理
        assertNull(nextSkipIndex(99, 3, Repeat.OFF))
        assertEquals(0, nextSkipIndex(99, 3, Repeat.ALL))
    }

    @Test
    fun previousSkip_walksBack() {
        assertEquals(1, previousSkipIndex(2, 5, Repeat.OFF))
        assertEquals(1, previousSkipIndex(2, 5, Repeat.ONE))
    }

    @Test
    fun previousSkip_atFirstWithoutListRepeat_restartsCurrent() {
        assertEquals("首曲点击上一首应回到本曲开头,而不是置灰", 0, previousSkipIndex(0, 5, Repeat.OFF))
    }

    @Test
    fun previousSkip_atFirstWithListRepeat_wrapsToLast() {
        assertEquals(4, previousSkipIndex(0, 5, Repeat.ALL))
    }

    @Test
    fun previousSkip_emptyQueue_hasNoTarget() {
        assertNull(previousSkipIndex(0, 0, Repeat.OFF))
    }

    @Test
    fun canSkipNext_matchesIndexAvailability() {
        assertTrue(canSkipNext(2, 5, Repeat.OFF))
        assertFalse(canSkipNext(4, 5, Repeat.OFF))
        assertTrue("列表循环下末曲仍可前进", canSkipNext(4, 5, Repeat.ALL))
    }

    @Test
    fun canSkipPrevious_onlyWhenQueueNotEmpty() {
        assertTrue(canSkipPrevious(3))
        assertFalse(canSkipPrevious(0))
    }

    @Test
    fun queuePositionLabel_isOneBasedAndClamped() {
        assertEquals(3, queuePositionLabel(2, 5))
        assertEquals(0, queuePositionLabel(0, 0))
        assertEquals(5, queuePositionLabel(99, 5))
        assertEquals(1, queuePositionLabel(-7, 5))
    }
}
