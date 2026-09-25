package io.github.adkimsm.neteasedownloader.net.unlock

import io.github.adkimsm.neteasedownloader.diag.Diag
import io.github.adkimsm.neteasedownloader.net.normalizeDownloadUrl

/**
 * 音源匹配器:按优先级在多个音源里找出**一条真正能播**的直链。
 *
 * 流程(每个音源):搜索 → 打分过滤 → 取分数最高的前
 * [MAX_CANDIDATES_PER_PROVIDER] 条 → 取直链 → `Range` 探活 → 首个成功即返回。
 *
 * 三重把关缺一不可:
 *  1. 打分([CandidateScorer])挡掉"同名但不是这首"(Live/翻唱/伴奏);
 *  2. 探活([ProviderHttp.probe])挡掉失效直链与 HTML 错误页;
 *  3. 实测时长([durationFromBytes] + [isPreviewClip])挡掉**试听片段** —— 酷我对受限歌曲
 *     会固定回一段约 11 秒的提示音,听起来像「这首歌只有 11 秒」;
 *  4. 只探前 N 条,避免一个音源打十几次请求 —— 手表上网络与电量都金贵。
 *
 * 任何一步失败都只是"这条候选不行",绝不向上抛异常:匹配失败的正确结果
 * 是让这首歌保持 [io.github.adkimsm.neteasedownloader.data.SongState.MISSING_URL],
 * 而不是把整轮同步带崩。
 */
class SourceMatcher(private val providers: List<SourceProvider>) {

    suspend fun match(
        http: ProviderHttp,
        query: SongQuery,
        providerIds: List<String>,
    ): MatchedSource? {
        for (providerId in providerIds) {
            val provider = providers.firstOrNull { it.id == providerId } ?: continue

            val candidates = runCatching { provider.candidates(http, query) }
                .onFailure { Diag.w(TAG, "音源 $providerId 搜索异常 songId=${query.songId}:${it.message}") }
                .getOrDefault(emptyList())

            val ranked = candidates
                .map { candidate -> candidate to CandidateScorer.score(query, candidate) }
                .filter { (_, score) -> score >= CandidateScorer.THRESHOLD }
                .sortedByDescending { (_, score) -> score }

            if (ranked.isEmpty()) {
                Diag.i(
                    TAG,
                    "音源 $providerId 无合格候选 songId=${query.songId} " +
                        "「${query.name}」(候选 ${candidates.size} 条)",
                )
                continue
            }

            for ((candidate, score) in ranked.take(MAX_CANDIDATES_PER_PROVIDER)) {
                val track = runCatching { provider.track(http, candidate) }.getOrNull() ?: continue
                // 规范化后再探活:与下载/播放走同一份规则(一律 https、去掉冗余 :443)。
                // 音源若返回 http,探活在真机上必失败(Android 禁明文),候选会被白白丢掉。
                val url = normalizeDownloadUrl(track.url)
                val probe = runCatching { http.probe(url) }.getOrNull() ?: continue

                // 以**实测**字节数为准:音源自报的 size 可能偏大,会把试听片段算成整曲
                val size = if (probe.totalBytes > 0) probe.totalBytes else track.size
                val br = if (track.br > 0) track.br else mp3Bitrate(probe.head) ?: 0L

                // 酷我对受限歌曲固定回一段约 11 秒的提示音(「仅在酷我音乐端可播放」),
                // 实测时长与标称时长差一个数量级 —— 这种片段必须丢掉,换下一个候选/音源。
                val actualSec = durationFromBytes(size, br)
                if (isPreviewClip(candidate.durationSec, actualSec)) {
                    Diag.i(
                        TAG,
                        "试听片段,跳过 $providerId songId=${query.songId} " +
                            "「${candidate.title}」标称=${candidate.durationSec}s 实际=${actualSec}s",
                    )
                    continue
                }
                Diag.i(
                    TAG,
                    "命中 $providerId songId=${query.songId} 「${candidate.title}」/" +
                        "${candidate.artist}/${candidate.durationSec}s score=$score size=$size br=$br",
                )
                return MatchedSource(
                    providerId = providerId,
                    url = url,
                    size = size,
                    br = br,
                    md5 = track.md5 ?: probe.serverMd5,
                    type = track.type.ifBlank { "mp3" },
                )
            }
        }
        return null
    }

    companion object {
        private const val TAG = "SourceMatcher"

        /** 每个音源最多探活几条候选。 */
        const val MAX_CANDIDATES_PER_PROVIDER = 3
    }
}

/**
 * 从字节数与码率反推时长(秒)。任一侧未知时返回 null,不要把没有证据的曲误判成试听。
 */
internal fun durationFromBytes(bytes: Long, br: Long): Int? =
    if (bytes <= 0L || br <= 0L) null else (bytes * 8 / br).toInt()

/**
 * 音源标了 [declaredSec] 秒,实测文件却不到一半:这是试听片段,不是这首歌。
 *
 * 酷我对受限歌曲会固定回一段约 11 秒的提示音(「仅在酷我音乐手机端可播放」),
 * 实测大小是 181521 字节,与标称时长(200 秒以上)差一个数量级。
 * 标称时长未知或码率读不出时不判,避免把拿不到证据的真曲误杀。
 */
internal fun isPreviewClip(declaredSec: Int, actualSec: Int?): Boolean {
    if (declaredSec <= 0 || actualSec == null) return false
    return actualSec * 2 < declaredSec
}
