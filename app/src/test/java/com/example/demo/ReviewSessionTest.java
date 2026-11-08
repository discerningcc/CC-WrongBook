package com.example.demo;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * ReviewSession 会话口径单测：完成计数、重做上限去重、最优结果归属、剩余题数。
 * 纯 Java 集合逻辑，不依赖 Android 框架。
 */
public class ReviewSessionTest {

    private static ReviewSession.Item item(String qid, String result, long answeredAt) {
        ReviewSession.Item it = new ReviewSession.Item();
        it.questionId = qid;
        it.result = result;
        it.answeredAt = answeredAt;
        return it;
    }

    private static ReviewSession session(ReviewSession.Item... items) {
        ReviewSession s = new ReviewSession();
        s.items = new ArrayList<>();
        for (ReviewSession.Item it : items) s.items.add(it);
        s.originalCount = s.items.size();
        return s;
    }

    @Test
    public void answeredCount_countsOnlyAnsweredItems() {
        ReviewSession s = session(
                item("q1", ReviewSession.RESULT_KNOW, 100),
                item("q2", null, 0),
                item("q3", ReviewSession.RESULT_SKIP, 300));
        assertEquals(2, s.answeredCount());
    }

    @Test
    public void answeredCount_nullItemsIsZero() {
        ReviewSession s = new ReviewSession();
        s.items = null;
        assertEquals(0, s.answeredCount());
    }

    @Test
    public void countOfResult_andSkippedCount() {
        ReviewSession s = session(
                item("q1", ReviewSession.RESULT_KNOW, 1),
                item("q2", ReviewSession.RESULT_KNOW, 2),
                item("q3", ReviewSession.RESULT_FORGET, 3),
                item("q4", ReviewSession.RESULT_SKIP, 4));
        assertEquals(2, s.countOfResult(ReviewSession.RESULT_KNOW));
        assertEquals(1, s.countOfResult(ReviewSession.RESULT_FORGET));
        assertEquals(1, s.skippedCount());
        assertEquals(0, s.countOfResult(null));
    }

    @Test
    public void remainingCount_clampsAtZero() {
        ReviewSession s = session(item("q1", ReviewSession.RESULT_KNOW, 1));
        s.currentIndex = 1;
        assertEquals(0, s.remainingCount());

        ReviewSession s2 = session(
                item("q1", ReviewSession.RESULT_KNOW, 1),
                item("q2", null, 0),
                item("q3", null, 0));
        s2.currentIndex = 1;
        assertEquals(2, s2.remainingCount());
    }

    @Test
    public void originalTotal_prefersOriginalCount_fallsBackToItemsSize() {
        ReviewSession s = session(item("q1", ReviewSession.RESULT_KNOW, 1));
        s.originalCount = 10;
        assertEquals(10, s.originalTotal());

        ReviewSession legacy = session(item("q1", ReviewSession.RESULT_KNOW, 1));
        legacy.originalCount = 0;
        assertEquals(1, legacy.originalTotal());
    }

    @Test
    public void closedOriginalCount_closesPositiveAndSkipAndExcluded() {
        ReviewSession s = session(
                item("know", ReviewSession.RESULT_KNOW, 1),
                item("vague", ReviewSession.RESULT_VAGUE, 2),
                item("skip", ReviewSession.RESULT_SKIP, 3),
                item("excl", ReviewSession.RESULT_EXCLUDED, 4));
        assertEquals(4, s.closedOriginalCount());
    }

    @Test
    public void closedOriginalCount_forgetStaysOpenUntilRedoCapExhausted() {
        // 首遍不会：occ=1 < 1+REDO_CAP，仍挂起未完成
        ReviewSession once = session(item("f", ReviewSession.RESULT_FORGET, 1));
        assertEquals(0, once.closedOriginalCount());

        // 重做副本又不会：occ=2 达到上限，算完成
        ReviewSession twice = session(
                item("f", ReviewSession.RESULT_FORGET, 1),
                item("f", ReviewSession.RESULT_FORGET, 2));
        assertEquals(1, twice.closedOriginalCount());
    }

    @Test
    public void closedOriginalCount_redoCopyDoesNotCreateSecondOriginal() {
        ReviewSession s = session(
                item("f", ReviewSession.RESULT_FORGET, 1),
                item("f", ReviewSession.RESULT_FORGET, 2),
                item("k", ReviewSession.RESULT_KNOW, 3));
        // f 达到上限算 1 道，k 算 1 道；副本不重复计数
        assertEquals(2, s.closedOriginalCount());
    }

    @Test
    public void uniqueStats_bucketsByBestResultPerQuestion() {
        ReviewSession s = session(
                item("know", ReviewSession.RESULT_KNOW, 1),
                item("vague", ReviewSession.RESULT_VAGUE, 2),
                item("forget", ReviewSession.RESULT_FORGET, 3),
                item("skip", ReviewSession.RESULT_SKIP, 4),
                item("excl", ReviewSession.RESULT_EXCLUDED, 5),
                // 同一题先不会后模糊：按最优归到模糊
                item("improved", ReviewSession.RESULT_FORGET, 6),
                item("improved", ReviewSession.RESULT_VAGUE, 7));
        int[] stats = s.uniqueStats();
        // 顺序：记得、模糊、不会、跳过、斩题
        assertEquals(1, stats[0]);
        assertEquals(2, stats[1]);
        assertEquals(1, stats[2]);
        assertEquals(1, stats[3]);
        assertEquals(1, stats[4]);
    }

    @Test
    public void uniqueStats_precedenceKnowBeatsEverything() {
        ReviewSession s = session(
                item("q", ReviewSession.RESULT_EXCLUDED, 1),
                item("q", ReviewSession.RESULT_SKIP, 2),
                item("q", ReviewSession.RESULT_VAGUE, 3),
                item("q", ReviewSession.RESULT_KNOW, 4));
        int[] stats = s.uniqueStats();
        assertEquals(1, stats[0]);
        assertEquals(0, stats[1]);
        assertEquals(0, stats[2]);
        assertEquals(0, stats[3]);
        assertEquals(0, stats[4]);
    }

    @Test
    public void lastAnsweredAt_takesMaxTimestamp() {
        ReviewSession s = session(
                item("q1", ReviewSession.RESULT_KNOW, 100),
                item("q2", ReviewSession.RESULT_VAGUE, 42),
                item("q3", null, 0));
        assertEquals(100, s.lastAnsweredAt());

        ReviewSession empty = new ReviewSession();
        empty.items = new ArrayList<>();
        assertEquals(0, empty.lastAnsweredAt());
    }

    @Test
    public void countsEncounter_includesKnowVagueForget() {
        assertTrue(ReviewSession.countsEncounter(ReviewSession.RESULT_KNOW, false));
        assertTrue(ReviewSession.countsEncounter(ReviewSession.RESULT_VAGUE, false));
        assertTrue(ReviewSession.countsEncounter(ReviewSession.RESULT_FORGET, false));
    }

    @Test
    public void countsEncounter_excludesSkipExcludedAndNull() {
        assertFalse(ReviewSession.countsEncounter(ReviewSession.RESULT_SKIP, false));
        assertFalse(ReviewSession.countsEncounter(ReviewSession.RESULT_EXCLUDED, false));
        assertFalse(ReviewSession.countsEncounter(null, false));
    }

    @Test
    public void countsEncounter_redoCopyNeverCounts() {
        assertFalse(ReviewSession.countsEncounter(ReviewSession.RESULT_KNOW, true));
        assertFalse(ReviewSession.countsEncounter(ReviewSession.RESULT_VAGUE, true));
        assertFalse(ReviewSession.countsEncounter(ReviewSession.RESULT_FORGET, true));
    }

    @Test
    public void isFinished_onlyFinishedStatusCounts() {
        ReviewSession active = session(item("q1", ReviewSession.RESULT_KNOW, 1));
        active.status = ReviewSession.STATUS_ACTIVE;
        assertFalse(active.isFinished());

        ReviewSession finished = session(item("q1", ReviewSession.RESULT_KNOW, 1));
        finished.status = ReviewSession.STATUS_FINISHED;
        assertTrue(finished.isFinished());

        ReviewSession abandoned = session(item("q1", ReviewSession.RESULT_KNOW, 1));
        abandoned.status = ReviewSession.STATUS_ABANDONED;
        assertFalse(abandoned.isFinished());
    }
}
