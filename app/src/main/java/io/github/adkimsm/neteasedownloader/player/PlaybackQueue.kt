package io.github.adkimsm.neteasedownloader.player

/**
 * 播放传输策略(纯函数,可单测)。
 *
 * **设计边界**:队列本身、自动续播、随机顺序与列表循环全部交给 ExoPlayer
 * (`setMediaItems` / `setRepeatMode` / `setShuffleModeEnabled` + `DefaultShuffleOrder`)。
 * 那部分 ExoPlayer 已经足够稳,而且随机的"当前曲不变、只改 next/prev 序列"语义
 * 自己重造一遍只会引入偏差。
 *
 * 这里只放两类东西:
 *  1. ExoPlayer 的语义与"手表上的手动切歌预期"不一致的地方 ——
 *     `REPEAT_MODE_ONE` 下 `seekToNextMediaItem()` 会重播当前曲,而用户点"下一首"是想前进;
 *  2. UI 需要提前判定的东西 —— 末尾 + 非列表循环时"下一首"无处可去,
 *     按钮应置灰,而不是按下去静默重播。
 */

/** 播放模式。与 `Player.REPEAT_MODE_*` 一一对应,但保持纯 Kotlin 以便单测。 */
enum class Repeat { OFF, ALL, ONE }

/** 播放模式循环:顺序 → 列表循环 → 单曲循环 → 顺序 */
fun nextRepeat(current: Repeat): Repeat = when (current) {
    Repeat.OFF -> Repeat.ALL
    Repeat.ALL -> Repeat.ONE
    Repeat.ONE -> Repeat.OFF
}

/**
 * 手动切歌是否需要临时绕开单曲循环。
 *
 * `seekToNextMediaItem()` / `seekToPreviousMediaItem()` 会遵循 repeatMode,
 * `REPEAT_MODE_ONE` 时返回的仍是当前曲目 —— 手动切歌必须先把 repeatMode 置 OFF、
 * 切完再恢复,否则"下一首"会变成"重播当前曲"。
 */
fun needsRepeatBypassForManualSkip(repeat: Repeat): Boolean = repeat == Repeat.ONE

/**
 * 手动"下一首"的目标下标(随机关闭时下标即播放序;随机关开时交给 ExoPlayer)。
 * 返回 null 表示无处可去:末尾且非列表循环。
 */
fun nextSkipIndex(current: Int, size: Int, repeat: Repeat): Int? {
    if (size <= 0) return null
    val index = current.coerceIn(0, size - 1)
    if (index + 1 < size) return index + 1
    return if (repeat == Repeat.ALL) 0 else null
}

/**
 * 手动"上一首"的目标下标。
 * 首曲时回到 0(重播当前曲)—— 与主流播放器一致,而不是置灰或空转;
 * 列表循环下则绕到末尾。
 */
fun previousSkipIndex(current: Int, size: Int, repeat: Repeat): Int? {
    if (size <= 0) return null
    val index = current.coerceIn(0, size - 1)
    if (index - 1 >= 0) return index - 1
    return if (repeat == Repeat.ALL) size - 1 else 0
}

/** "下一首"按钮是否可用(末尾 + 非列表循环 → 置灰) */
fun canSkipNext(current: Int, size: Int, repeat: Repeat): Boolean =
    nextSkipIndex(current, size, repeat) != null

/** "上一首"按钮是否可用:只要有歌就可用(首曲点击 = 重播) */
fun canSkipPrevious(size: Int): Boolean = size > 0

/** 队列位置展示用:当前曲在队列中的序号(1 起);空队列返回 0 */
fun queuePositionLabel(current: Int, size: Int): Int =
    if (size <= 0) 0 else current.coerceIn(0, size - 1) + 1
