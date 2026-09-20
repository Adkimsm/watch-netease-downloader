package io.github.adkimsm.neteasedownloader.ui

/**
 * 同步剩余时间估算。
 *
 * 用**滑动窗口速率**而非全局平均:
 * 大歌单(3742 首)首轮同步时,开头的网络握手/去重阶段会拖慢全局平均,
 * 用它算出的 ETA 会长期严重偏高,反而让用户以为卡住了。
 * 滑动窗口只反映最近的完成速度,几次采样后即收敛到真实值。
 *
 * 纯函数 + 显式传入 [now](不读系统时钟),便于确定性单测。
 */
class EtaEstimator(
    /** 每个样本记为 (完成时刻 ms, 当时已完成数) */
    private val windowSize: Int = WINDOW_SIZE,
    /** 样本间最小间隔,避免高频 update 把窗口挤满同一秒的数据 */
    private val minSampleIntervalMs: Long = MIN_SAMPLE_INTERVAL_MS,
) {
    private val samples = ArrayDeque<Sample>()

    private data class Sample(val atMs: Long, val done: Int)

    /** 清空(新一轮同步开始时调用) */
    fun reset() {
        samples.clear()
    }

    /**
     * 记录一次进度。
     * @param done 当前已完成数
     * @param nowMs 当前时刻
     */
    fun record(done: Int, nowMs: Long) {
        val last = samples.lastOrNull()
        // 间隔太短的样本丢弃,保持窗口内样本的时间跨度有信息量
        if (last != null && nowMs - last.atMs < minSampleIntervalMs) {
            return
        }
        samples.addLast(Sample(nowMs, done))
        while (samples.size > windowSize) samples.removeFirst()
    }

    /**
     * 估算剩余秒数。
     *
     * @return null 表示样本不足以估算(UI 应隐藏 ETA,而不是显示 0 或乱算的值)
     */
    fun estimateSeconds(done: Int, total: Int, nowMs: Long): Long? {
        if (total <= 0 || done <= 0) return null
        val remaining = total - done
        if (remaining <= 0) return 0L

        val first = samples.firstOrNull() ?: return null
        val last = samples.lastOrNull() ?: return null
        // 至少要有两个样本且时间有推进,才能算出速率
        if (samples.size < 2) return null
        val elapsedMs = last.atMs - first.atMs
        if (elapsedMs <= 0) return null

        val completed = last.done - first.done
        if (completed <= 0) return null

        val msPerItem = elapsedMs.toDouble() / completed
        // 用 nowMs 与最后样本的差补齐窗口外的部分
        val sinceLastMs = (nowMs - last.atMs).coerceAtLeast(0L)
        val remainingMs = msPerItem * remaining + sinceLastMs
        return (remainingMs / 1000.0).toLong().coerceAtLeast(0L)
    }

    companion object {
        /** 最近 8 个样本 */
        private const val WINDOW_SIZE = 8
        private const val MIN_SAMPLE_INTERVAL_MS = 1_000L
    }
}
