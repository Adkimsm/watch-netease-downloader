package io.github.adkimsm.neteasedownloader.net.unlock

import io.github.adkimsm.neteasedownloader.diag.Diag

/**
 * 音源匹配器:按优先级在多个音源里找出**一条真正能播**的直链。
 *
 * 流程(每个音源):搜索 → 打分过滤 → 取分数最高的前
 * [MAX_CANDIDATES_PER_PROVIDER] 条 → 取直链 → `Range` 探活 → 首个成功即返回。
 *
 * 三重把关缺一不可:
 *  1. 打分([CandidateScorer])挡掉"同名但不是这首"(Live/翻唱/伴奏);
 *  2. 探活([ProviderHttp.probe])挡掉失效直链与 HTML 错误页;
 *  3. 只探前 N 条,避免一个音源打十几次请求 —— 手表上网络与电量都金贵。
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
                val probe = runCatching { http.probe(track.url) }.getOrNull() ?: continue

                val size = maxOf(track.size, probe.totalBytes)
                val br = if (track.br > 0) track.br else mp3Bitrate(probe.head) ?: 0L
                Diag.i(
                    TAG,
                    "命中 $providerId songId=${query.songId} 「${candidate.title}」/" +
                        "${candidate.artist}/${candidate.durationSec}s score=$score size=$size br=$br",
                )
                return MatchedSource(
                    providerId = providerId,
                    url = track.url,
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
