package io.github.adkimsm.neteasedownloader.library

/**
 * 「离线删除」的排队决策(纯函数,可单测)。
 *
 * 用户的故事:在地铁上/飞行模式下听到难听的歌 → 删掉。本地文件当场就能删干净,
 * 但「从歌单移除」和「取消红心」是远端写操作,没网时只能等。于是把**远端那一半**
 * 记进队列,联网后统一执行 —— 本地那一半绝不拖延,否则用户会以为点了没反应。
 *
 * 三条不变量(每一条都容易被改坏,所以放在这里由单测钉住):
 *
 *  1. **只有真的有远端工作才排队**。既不在本人歌单、也没红心的歌,删除本来就不需要网络,
 *     没网也当场删掉 —— 排队只会让用户白等一次联网。
 *  2. **重复删除是合并,不是追加**。同一首歌连点两次不能产生两条队列项,否则联网后会
 *     对同一个歌单删两次、红心也取消两次。
 *  3. **执行完只留下失败的那部分**。请求成功但写后读回发现服务端还留着的([RemoveReport.remoteStale])
 *     也算失败 —— 它才是这个接口最容易骗人的地方。
 */
data class PendingRemoval(
    val songId: Long,
    /** 还要从哪些歌单移除(按 id 序,便于日志与去重) */
    val playlistIds: List<Long> = emptyList(),
    /** 还要取消红心 */
    val unlike: Boolean = false,
    /** 入队时间,决定执行顺序(先删的先执行) */
    val createdAt: Long = 0L,
) {
    /** 没有可执行的远端工作 */
    val isEmpty: Boolean get() = playlistIds.isEmpty() && !unlike
}

/**
 * 这次删除要不要排队等联网。
 *
 * [online] 由调用方在**执行前**重新判断:删除面板可能开着的时候网络就回来了,
 * 那时应当直接执行,而不是把一件已经能做的事丢进队列。
 */
fun shouldQueue(outcome: RemoveOutcome, online: Boolean): Boolean =
    !online && (outcome.remoteTargets.isNotEmpty() || outcome.unlike)

/**
 * 同一首歌再次入队:歌单取并集、红心取或,入队时间保持最早那次。
 * 保持原时间是为了让队列顺序稳定(先排队的先执行),也让日志可读。
 */
fun mergePending(existing: PendingRemoval?, incoming: PendingRemoval): PendingRemoval {
    if (existing == null) return incoming
    return PendingRemoval(
        songId = incoming.songId,
        playlistIds = (existing.playlistIds + incoming.playlistIds).distinct().sorted(),
        unlike = existing.unlike || incoming.unlike,
        createdAt = existing.createdAt,
    )
}

/**
 * 队列项 → 执行用的计划。
 *
 * [RemoveOutcome.deleteLocal] 恒为 false:本地文件在入队那一刻就已经删掉了,
 * 联网后重跑一遍只会对同一个 songId 再删一次(而且它可能已经被下一轮同步回收了行)。
 * [RemoveOutcome.survivingEnabled] 同理为空 —— 文件已经不在,不存在"下回来"的问题。
 */
fun flushOutcomeFor(entry: PendingRemoval): RemoveOutcome = RemoveOutcome(
    remoteTargets = entry.playlistIds,
    unlike = entry.unlike,
    deleteLocal = false,
    survivingEnabled = emptyList(),
)

/**
 * 一次执行之后还要留在队列里的部分;全部成功返回 null(整条可以删掉)。
 *
 * 成功的歌单已经由 [SongRemover.remove] 清掉本地关联,不该再执行;
 * 失败与写后读回不一致的必须留着等下一轮 —— 悄悄丢掉等于骗用户"删好了"。
 */
fun remainingAfterFlush(entry: PendingRemoval, report: RemoveReport): PendingRemoval? {
    val stillRemote = (report.remoteFailed.map { it.playlistId } + report.remoteStale).toHashSet()
    val playlistIds = entry.playlistIds.filter { it in stillRemote }
    val unlike = entry.unlike && report.unlikeOk != true
    if (playlistIds.isEmpty() && !unlike) return null
    return entry.copy(playlistIds = playlistIds, unlike = unlike)
}

/**
 * 丢弃指向**已经不存在**的歌单的目标。
 *
 * 歌单在 App 里被删掉之后,队列里那条"从它移除"永远不可能成功,留着就是每次联网
 * 都白跑一次请求、还让歌曲的「待删除」徽标永远不消失。丢弃是安全的:歌单都没了,
 * 也就不存在"这首歌还在那个歌单里"的问题。
 */
fun pruneTargets(entry: PendingRemoval, knownPlaylistIds: Set<Long>): PendingRemoval? {
    val playlistIds = entry.playlistIds.filter { it in knownPlaylistIds }
    if (playlistIds.isEmpty() && !entry.unlike) return null
    return entry.copy(playlistIds = playlistIds)
}
