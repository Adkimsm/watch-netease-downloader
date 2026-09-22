package io.github.adkimsm.neteasedownloader.sync

import io.github.adkimsm.neteasedownloader.data.SongEntity

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
