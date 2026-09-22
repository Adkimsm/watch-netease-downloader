package io.github.adkimsm.neteasedownloader.library

/** 单个歌单的远端失败 */
data class RemoteFailure(val playlistId: Long, val message: String)

/**
 * 一次删除的执行结果。
 *
 * [remoteOk] 是**请求成功**的歌单,[remoteStale] 是请求成功但**写后读回发现服务端其实还在**
 * 的歌单 —— 后者才是这个接口最容易骗人的地方(返回 200 却不生效),分开记才能定位问题。
 */
data class RemoveReport(
    val songId: Long,
    val fileDeleted: Boolean,
    val remoteOk: List<Long> = emptyList(),
    val remoteFailed: List<RemoteFailure> = emptyList(),
    val unlikeOk: Boolean? = null,
    val remoteStale: List<Long> = emptyList(),
) {
    val hasFailure: Boolean
        get() = remoteFailed.isNotEmpty() || remoteStale.isNotEmpty() || unlikeOk == false
}

data class UndoReport(
    val restoredPlaylists: List<Long> = emptyList(),
    val reliked: Boolean = false,
    val failures: List<RemoteFailure> = emptyList(),
)

/**
 * 撤销计划:把刚刚真正改动过的东西加回去。
 *
 * 只撤销**确实成功了**的部分 —— 失败的那些本来就没动过,再去加一遍反而会重复。
 * 本地文件不在撤销范围内:它由下一次同步自然下回,当场重新下载只会让撤销按钮
 * 变成一个会卡住几秒的按钮。
 */
data class UndoPlan(
    val playlistIds: List<Long> = emptyList(),
    val relike: Boolean = false,
) {
    val isEmpty: Boolean get() = playlistIds.isEmpty() && !relike
}

fun undoPlanFor(outcome: RemoveOutcome, report: RemoveReport): UndoPlan = UndoPlan(
    playlistIds = report.remoteOk,
    relike = outcome.unlike && report.unlikeOk == true,
)
