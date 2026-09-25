package io.github.adkimsm.neteasedownloader.net.unlock

import java.text.Normalizer
import kotlin.math.abs

/**
 * 候选打分:决定"搜到的这一条到底是不是我们要的那首歌"。
 *
 * 为什么必须有它:参考实现是**直接取搜索结果第 1 条**,而实测酷狗搜「晴天 周杰伦」
 * 的首条是「晴天 (Live)」134s,目标原版是 269s —— 盲取第一条会播错版本。
 * 这里用「标题 + 歌手 + 时长」三维打分,并且**时长差超过
 * [MAX_DURATION_DELTA_SEC] 直接判 0**(Live/翻唱/伴奏/试听片段都靠它挡掉)。
 *
 * 纯函数,单测见 CandidateScorerTest。
 */
object CandidateScorer {

    /** 低于此分视为没匹配到 —— 宁可不播,也不播错版本。 */
    const val THRESHOLD = 60

    /** 时长差超过这个秒数直接判 0。 */
    const val MAX_DURATION_DELTA_SEC = 20

    /** 括号限定词:`(Live)`、`（翻自 xxx）`、`(DJ 阿Bo版)` 一律整段去掉。 */
    private val PARENTHESIZED = Regex("[（(][^（()）]*[)）]")

    private val ARTIST_SEPARATORS = charArrayOf('/', '、', '&', ',', '，', ';', '；', '|')

    /**
     * 归一化:全角→半角、去掉括号限定词、小写、只留字母与数字。
     *
     * 只留字母数字是刻意的:音源标题里的空格/连字符/波浪线五花八门,
     * 留着它们会让「晴天 - 周杰伦」和「晴天」判不相等。
     */
    fun normalize(raw: String): String {
        val halfWidth = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        return PARENTHESIZED.replace(halfWidth, " ")
            .lowercase()
            .filter { it.isLetterOrDigit() }
    }

    /** 时长差(秒);任一侧时长未知时返回 null(按"无证据"处理,既不拒绝也不加分)。 */
    fun durationDeltaSec(query: SongQuery, candidate: SourceCandidate): Int? {
        if (query.durationMs <= 0L || candidate.durationSec <= 0) return null
        return abs(query.durationMs / 1000L - candidate.durationSec).toInt()
    }

    fun score(query: SongQuery, candidate: SourceCandidate): Int {
        val target = normalize(query.name)
        val title = normalize(candidate.title)
        if (target.isEmpty() || title.isEmpty()) return 0

        val delta = durationDeltaSec(query, candidate)
        if (delta != null && delta > MAX_DURATION_DELTA_SEC) return 0

        // 标题对不上就直接出局:歌手与时长再吻合也不能把一首别的歌捞回来
        var score = when {
            title == target -> 60
            title.contains(target) || target.contains(title) -> 40
            else -> return 0
        }
        if (artistMatches(query.artist, candidate.artist)) score += 20
        if (delta != null) {
            score += when {
                delta <= 3 -> 20
                delta <= 8 -> 10
                else -> 0
            }
        }
        return score
    }

    private fun artistMatches(queryArtist: String, candidateArtist: String): Boolean {
        val queryTokens = artistTokens(queryArtist)
        val candidateTokens = artistTokens(candidateArtist)
        if (queryTokens.isEmpty() || candidateTokens.isEmpty()) return false
        return queryTokens.any { query ->
            candidateTokens.any { candidate ->
                candidate == query || candidate.contains(query) || query.contains(candidate)
            }
        }
    }

    /** 歌手按「/、、&,，;；|」拆开分别归一:合唱曲两边写法常常不一致。 */
    private fun artistTokens(raw: String): List<String> =
        raw.split(*ARTIST_SEPARATORS)
            .map { normalize(it) }
            .filter { it.isNotEmpty() }
}
