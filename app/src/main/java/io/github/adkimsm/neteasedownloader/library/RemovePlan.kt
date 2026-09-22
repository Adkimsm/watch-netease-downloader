package io.github.adkimsm.neteasedownloader.library

/**
 * 「删除这首歌」的范围决策(纯函数,可单测)。
 *
 * 用户的故事很短:听到难听的歌 → 删掉 → 不想在任何地方再遇到它。落到实现上要拆成
 * 三件独立的事,而每件都可能做不到:
 *
 *  1. 从**我拥有的**歌单里移除(他人歌单接口会拒绝);
 *  2. **自动取消红心**(不是可选项 —— 留着红心等于这首刚删掉的歌还挂在「我喜欢的音乐」里,
 *     而那个歌单一旦勾了同步就会把它下回来);
 *  3. 删除本地文件。
 *
 * 本文件只负责把「模式 + 面板勾选」翻译成执行计划,以及**提前算出后果**:
 * 删完以后还有哪些已勾选歌单含这首歌 —— 那些歌下一轮同步会被重新下载,
 * 必须在用户按下删除**之前**就说清楚。
 */

/** 删除歌曲时如何处理远端。对应设置页的三档。 */
enum class RemoveScope { ASK, ALL, LOCAL_ONLY }

/** 设置项的字符串 ↔ 枚举。非法值(旧版本残留、手改)一律回落 [RemoveScope.ASK]。 */
fun removeScopeFrom(raw: String?): RemoveScope =
    RemoveScope.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: RemoveScope.ASK

/** 用户在删除面板上的选择 */
data class RemoveSelection(
    /** 勾选的本人歌单 */
    val playlistIds: Set<Long> = emptySet(),
    /** 只删本地文件,不动任何歌单 */
    val localOnly: Boolean = false,
    /** 保留本地文件,只整理歌单(与 [localOnly] 互斥) */
    val keepLocal: Boolean = false,
)

/** 一次删除实际要做什么 */
data class RemoveOutcome(
    /** 要调 track/delete 的歌单(按名称序,便于日志与撤销) */
    val remoteTargets: List<Long>,
    /** 是否取消红心。== 该曲是否在红心里,与模式无关。 */
    val unlike: Boolean,
    /** 是否删除本地文件 */
    val deleteLocal: Boolean,
    /**
     * 删除后**仍**含这首歌、且已勾选同步的歌单 —— 它们会让这首歌在下一轮同步被重新下载。
     * [deleteLocal] 为 false 时恒为空(文件本来就没删,不存在"下回来"的问题)。
     */
    val survivingEnabled: List<PresenceEntry>,
)

/** 面板默认选择:本人歌单全选 */
fun defaultSelection(presence: SongPresence): RemoveSelection =
    RemoveSelection(playlistIds = presence.ownedPlaylists.map { it.playlistId }.toSet())

/**
 * 把「模式 + 勾选」翻译成执行计划。
 *
 * [selection] 只在 [RemoveScope.ASK] 下有意义;另外两档是"点了就删"的快模式,
 * 传 null 即按默认语义执行。
 */
fun planFor(
    scope: RemoveScope,
    presence: SongPresence,
    selection: RemoveSelection? = null,
): RemoveOutcome {
    val choice = selection ?: defaultSelection(presence)

    val targets: List<Long> = when (scope) {
        RemoveScope.LOCAL_ONLY -> emptyList()

        RemoveScope.ALL -> presence.ownedPlaylists.map { it.playlistId }

        RemoveScope.ASK -> if (choice.localOnly) {
            emptyList()
        } else {
            presence.ownedPlaylists
                .filter { it.playlistId in choice.playlistIds }
                .map { it.playlistId }
        }
    }

    val deleteLocal = when (scope) {
        RemoveScope.ALL, RemoveScope.LOCAL_ONLY -> true
        // 面板上的两个开关是互斥的(勾一个自动取消另一个)。真出现两个都勾的异常状态时,
        // 以"只删本地"为准 —— 它是更明确的那一个,而 !keepLocal 会让这次删除什么也不做。
        RemoveScope.ASK -> if (choice.localOnly) true else !choice.keepLocal
    }

    val surviving = if (!deleteLocal) {
        emptyList()
    } else {
        presence.playlists.filter { it.enabled && it.playlistId !in targets }
    }

    return RemoveOutcome(
        remoteTargets = targets,
        // D14:红心一律自动取消 —— 与模式、与勾选都无关
        unlike = presence.liked,
        deleteLocal = deleteLocal,
        survivingEnabled = surviving,
    )
}

/** 确认按钮的文案参数:要移除几个歌单 */
fun removeTargetCount(outcome: RemoveOutcome): Int = outcome.remoteTargets.size
