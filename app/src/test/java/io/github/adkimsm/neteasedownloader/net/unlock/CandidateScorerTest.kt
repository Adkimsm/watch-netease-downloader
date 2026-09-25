package io.github.adkimsm.neteasedownloader.net.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打分器:决定"搜到的这一条是不是我们要的那首歌"。
 *
 * 核心用例直接吃**真实酷狗响应**:搜「晴天 周杰伦」的首条是「晴天 (Live)」134s,
 * 目标是 269s 的原版 —— 参考实现取第一条的写法在这里会播错版本。
 */
class CandidateScorerTest {

    private val query = SongQuery(songId = 186016, name = "晴天", artist = "周杰伦", durationMs = 269_000)

    @Test
    fun kugouFixture_rejectsLiveClipAndKeepsFullLength() {
        val candidates = KugouProvider.parseSearch(Fixtures.text("kugou_search.json"))
        assertEquals("fixture 应有 3 条候选", 3, candidates.size)

        val liveClip = candidates.first { it.durationSec == 134 }
        assertEquals("134s 的 Live 片段必须判 0", 0, CandidateScorer.score(query, liveClip))

        val best = candidates.maxBy { CandidateScorer.score(query, it) }
        assertEquals("应选中 261s 的完整版而不是 134s 的片段", 261, best.durationSec)
        assertTrue(
            "最佳候选必须过阈值",
            CandidateScorer.score(query, best) >= CandidateScorer.THRESHOLD,
        )
    }

    @Test
    fun normalize_stripsQualifiersAndFoldsWidth() {
        assertEquals("晴天", CandidateScorer.normalize("晴天 (Live)"))
        assertEquals("晴天", CandidateScorer.normalize("晴天（Live）"))
        assertEquals("晴天", CandidateScorer.normalize("晴天 (DJ 阿Bo版)"))
        assertEquals("晴天", CandidateScorer.normalize("  晴天  "))
        assertEquals("abc", CandidateScorer.normalize("ＡＢＣ"))
    }

    @Test
    fun durationFarOff_isRejectedEvenWithExactTitle() {
        val candidate = SourceCandidate("1", "晴天", "周杰伦", 100)
        assertEquals(0, CandidateScorer.score(query, candidate))
    }

    @Test
    fun differentTitle_isRejectedEvenWhenArtistAndDurationMatch() {
        val candidate = SourceCandidate("1", "七里香", "周杰伦", 269)
        assertEquals(0, CandidateScorer.score(query, candidate))
    }

    @Test
    fun artistMismatch_dropsBelowThreshold() {
        // 标题只是"包含"(40) + 时长差 8s(10) = 50 < 60
        val candidate = SourceCandidate("1", "晴天 钢琴版", "某某乐团", 261)
        val score = CandidateScorer.score(query, candidate)
        assertTrue("歌手不符时不该过阈值,实际 $score", score < CandidateScorer.THRESHOLD)
    }

    @Test
    fun unknownDuration_neitherRejectsNorAdds() {
        val candidate = SourceCandidate("1", "晴天", "周杰伦", 0)
        assertEquals("标题 60 + 歌手 20,时长未知不加分", 80, CandidateScorer.score(query, candidate))
    }

    @Test
    fun multiArtistTokens_matchAcrossSeparators() {
        val duet = SongQuery(songId = 1, name = "因为爱情", artist = "陈奕迅/王菲", durationMs = 200_000)
        val candidate = SourceCandidate("1", "因为爱情", "王菲、陈奕迅", 200)
        assertTrue(CandidateScorer.score(duet, candidate) >= CandidateScorer.THRESHOLD)
    }

    @Test
    fun emptyTitles_scoreZero() {
        assertEquals(0, CandidateScorer.score(query, SourceCandidate("1", "", "周杰伦", 269)))
    }
}
