package io.github.adkimsm.neteasedownloader.sync

import io.github.adkimsm.neteasedownloader.data.SongEntity
import io.github.adkimsm.neteasedownloader.data.SongState

/**
 * 同步的"该删哪些本地文件"决策(纯函数,可单测)。
 *
 * 规则只有两条,但两条都容易被改坏:
 * 1. **只看有没有本地文件**:待删集合 = 「本地有文件的歌」− 「已勾选歌单的远端曲目」。
 *    不能用"所有 song 行"来算 —— 浏览过的未勾选歌单会留下未下载的元数据缓存,
 *    那些行每轮同步都被判成待删的话,缓存就永远留不住,歌单详情页每进一次都要重拉。
 *    这样磁盘上的文件集合仍严格等于"已勾选歌单的并集",与改造前的语义一致。
 *
 *    "有本地文件"这个谓词刻意放在**函数内部**而不是交给调用方先过滤:
 *    它是一条不变量,写在校验不到的地方就迟早会被漏掉。
 *
 * 2. **正在播放的那一首本轮豁免**:用户听着歌点同步,把正在听的文件删掉是明确的
 *    体验缺陷。豁免是自愈的 —— 下一轮同步它不再受保护,自然会被清掉。
 */
fun planLocalDeletions(
    songs: List<SongEntity>,
    enabledRemoteIds: Set<Long>,
    playingSongId: Long? = null,
): List<SongEntity> = songs.filter { song ->
    song.localUri != null &&
        song.songId !in enabledRemoteIds &&
        song.songId != playingSongId
}

/**
 * 同步的「该下哪些歌」决策(纯函数,可单测)。
 *
 * 两条规则:
 *  1. 本地没有完整文件(`state != OK`)才需要下;
 *  2. **已经排队待删的歌一律排除**。它们的远端删除还没落地,本地歌单关联也还在,
 *     只按「缺文件」判断的话,下一轮同步就会把用户刚在离线时删掉的歌重新下载回来 ——
 *     队列执行失败时尤其明显(那时远端还留着,拉回来一看就是「缺文件」)。
 *
 * 第 2 条是这条链路上最容易漏掉的不变量,所以它和待删集合一样放在纯函数里由单测钉住。
 */
fun planDownloadCandidates(songs: List<SongEntity>, excludedIds: Set<Long>): List<SongEntity> =
    songs.filter { it.state != SongState.OK.name && it.songId !in excludedIds }
