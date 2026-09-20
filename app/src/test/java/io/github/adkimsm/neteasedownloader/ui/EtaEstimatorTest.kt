package io.github.adkimsm.neteasedownloader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EtaEstimator 的确定性测试。
 *
 * 所有时间戳都由测试显式传入,不读系统时钟 —— 保证结果可复现。
 */
class EtaEstimatorTest {

    private fun estimator() = EtaEstimator(windowSize = 8, minSampleIntervalMs = 1_000L)

    @Test
    fun noSamplesYieldsNull() {
        val e = estimator()
        assertNull(e.estimateSeconds(done = 0, total = 100, nowMs = 1_000))
    }

    @Test
    fun singleSampleIsNotEnough() {
        val e = estimator()
        e.record(done = 1, nowMs = 1_000)
        // 只有一个样本无法算出速率,应返回 null 而不是瞎猜
        assertNull(e.estimateSeconds(done = 1, total = 100, nowMs = 2_000))
    }

    @Test
    fun zeroDoneOrTotalYieldsNull() {
        val e = estimator()
        e.record(0, 0)
        e.record(5, 5_000)
        assertNull(e.estimateSeconds(done = 0, total = 100, nowMs = 6_000))
        assertNull(e.estimateSeconds(done = 5, total = 0, nowMs = 6_000))
    }

    @Test
    fun completedReturnsZero() {
        val e = estimator()
        e.record(1, 1_000)
        e.record(10, 10_000)
        assertEquals(0L, e.estimateSeconds(done = 10, total = 10, nowMs = 11_000))
    }

    /** 每首 1 秒:已完成 2 首、剩 8 首,应约剩 8 秒 */
    @Test
    fun steadyRateEstimatesRemaining() {
        val e = estimator()
        e.record(done = 0, nowMs = 0)
        e.record(done = 1, nowMs = 1_000)
        e.record(done = 2, nowMs = 2_000)
        val eta = e.estimateSeconds(done = 2, total = 10, nowMs = 2_000)
        assertNotNull(eta)
        assertEquals(8L, eta!!)
    }

    /**
     * 慢启动后提速:滑动窗口应跟着收敛,而不是被开头的高耗时长期拖住。
     * 全局平均在此场景会显著高估。
     */
    /**
     * 慢启动阶段:滑动窗口内的样本仍包含开头的慢速项,但估算值必须优于(小于)全局平均。
     * 这正是本类存在的理由 —— 全局平均在大歌单首轮同步时会长期高估。
     */
    @Test
    fun slidingWindowBeatsGlobalAverageDuringSlowStart() {
        val e = estimator()
        // 前 3 首各花 10 秒
        e.record(done = 0, nowMs = 0)
        e.record(done = 1, nowMs = 10_000)
        e.record(done = 2, nowMs = 20_000)
        e.record(done = 3, nowMs = 30_000)
        // 之后每首 1 秒
        e.record(done = 4, nowMs = 31_000)
        e.record(done = 5, nowMs = 32_000)
        e.record(done = 6, nowMs = 33_000)
        e.record(done = 7, nowMs = 34_000)
        e.record(done = 8, nowMs = 35_000)

        val eta = e.estimateSeconds(done = 8, total = 100, nowMs = 35_000)
        assertNotNull(eta)
        // 全局平均 = 35s / 8 首 * 92 首 ≈ 402s
        val globalAverage = 35_000.0 / 8 * 92 / 1000
        assertTrue(
            "滑动窗口应优于全局平均(实际=${eta}s,全局平均≈${globalAverage.toInt()}s)",
            eta!! < globalAverage.toLong(),
        )
    }

    /** 快样本足够填满窗口后,估算应完全收敛到当前速率 */
    @Test
    fun slidingWindowFullyConvergesOnceWindowIsFast() {
        val e = estimator()
        e.record(done = 0, nowMs = 0)
        e.record(done = 1, nowMs = 10_000) // 一个慢样本,随后被挤出窗口
        for (i in 2..12) {
            e.record(done = i, nowMs = 10_000L + (i - 1) * 1_000L)
        }
        // 窗口现在只含每首 1 秒的样本;剩 100-12=88 首 -> 约 88 秒
        val eta = e.estimateSeconds(done = 12, total = 100, nowMs = 21_000)
        assertNotNull(eta)
        assertTrue("窗口被快样本填满后应收敛,实际=${eta}s", eta!! in 80L..100L)
    }

    @Test
    fun windowKeepsOnlyRecentSamples() {
        val e = EtaEstimator(windowSize = 3, minSampleIntervalMs = 1_000L)
        // 10 个样本,窗口只保留最后 3 个
        for (i in 0..9) {
            e.record(done = i, nowMs = i * 1_000L)
        }
        val eta = e.estimateSeconds(done = 9, total = 20, nowMs = 9_000)
        assertNotNull(eta)
        // 最后 3 个样本跨越 2 秒、完成 2 首 -> 每首 1 秒,剩 11 首
        assertEquals(11L, eta!!)
    }

    @Test
    fun resetClearsSamples() {
        val e = estimator()
        e.record(done = 1, nowMs = 1_000)
        e.record(done = 2, nowMs = 2_000)
        e.reset()
        assertNull(e.estimateSeconds(done = 2, total = 10, nowMs = 3_000))
    }

    @Test
    fun staleSamplesDoNotProduceNegativeEta() {
        val e = estimator()
        e.record(done = 0, nowMs = 0)
        e.record(done = 1, nowMs = 1_000)
        // nowMs 早于最后样本(时钟回拨)也不应返回负数
        val eta = e.estimateSeconds(done = 1, total = 10, nowMs = 500)
        assertNotNull(eta)
        assertTrue("ETA 不应为负,实际=${eta}", eta!! >= 0)
    }
}
