package io.github.adkimsm.neteasedownloader.data

import io.github.adkimsm.neteasedownloader.library.PendingRemoval
import io.github.adkimsm.neteasedownloader.library.mergePending
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 待删除队列的一次快照:曲目行徽标、首页入口计数与同步差量都读它 */
data class PendingRemovalState(val entries: List<PendingRemoval> = emptyList()) {
    val count: Int get() = entries.size

    /** 这些歌的远端删除还没落地 —— 同步时绝不能被当成「缺文件」重新下载 */
    val songIds: Set<Long> get() = entries.mapTo(HashSet()) { it.songId }

    val isEmpty: Boolean get() = entries.isEmpty()

    companion object {
        val EMPTY = PendingRemovalState()
    }
}

/**
 * 待删除队列的可观察外壳。
 *
 * 队列会被**两条互不相干的路径**改动:用户点删除(ViewModel)与联网后的自动执行(App 的网络回调)。
 * 把「写库 + 刷新快照」收在同一个地方,UI 只订阅 [state],就不必知道这次是谁改的 ——
 * 自动执行发生在 ViewModel 之外,没有这层就刷不出徽标。
 */
class PendingRemovalStore(private val dao: PendingRemovalDao) {
    private val _state = MutableStateFlow(PendingRemovalState.EMPTY)
    val state: StateFlow<PendingRemovalState> = _state.asStateFlow()

    suspend fun reload() {
        _state.value = PendingRemovalState(dao.all())
    }

    /** 执行前读一份:执行过程中用户可能取消某条,不该边执行边读队列 */
    suspend fun snapshot(): List<PendingRemoval> = dao.all()

    /**
     * 入队。同一首歌重复入队即合并(歌单取并集、红心取或、保留最早入队时间)——
     * 合并规则本身在纯函数 [mergePending] 里,这里只负责读旧值、算新值、写回去。
     */
    suspend fun enqueue(songId: Long, playlistIds: List<Long>, unlike: Boolean) {
        val existing = dao.all().firstOrNull { it.songId == songId }
        val incoming = PendingRemoval(
            songId = songId,
            playlistIds = playlistIds.distinct().sorted(),
            unlike = unlike,
            createdAt = System.currentTimeMillis(),
        )
        dao.upsert(mergePending(existing, incoming))
        reload()
    }

    /** 执行结果回写;[remaining] 为 null 表示这一条已经做完 */
    suspend fun apply(songId: Long, remaining: PendingRemoval?) {
        if (remaining == null) dao.deleteSong(songId) else dao.upsert(remaining)
        reload()
    }

    suspend fun cancel(songId: Long) {
        dao.deleteSong(songId)
        reload()
    }

    suspend fun clearAll() {
        dao.deleteAll()
        reload()
    }
}
