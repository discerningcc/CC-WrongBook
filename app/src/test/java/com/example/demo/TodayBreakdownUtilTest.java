package com.example.demo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 今柱构成拆分单测：刚好到期、从未作答的 E、今天降为 E、以往积压 E、逾期/未到期不参与。
 */
public class TodayBreakdownUtilTest {

    @Test
    public void due_reviewedBeforeAndIntervalLandsToday() {
        assertEquals(TodayBreakdownUtil.DUE, TodayBreakdownUtil.classify(7, 7, 3));
        assertEquals(TodayBreakdownUtil.DUE, TodayBreakdownUtil.classify(1, 1, 1));
        assertEquals(TodayBreakdownUtil.DUE, TodayBreakdownUtil.classify(30, 30, 9));
    }

    @Test
    public void newQuestion_neverAnsweredIsE() {
        assertEquals(TodayBreakdownUtil.NEW_NEVER_ANSWERED, TodayBreakdownUtil.classify(0, 0, 0));
        assertEquals(TodayBreakdownUtil.NEW_NEVER_ANSWERED, TodayBreakdownUtil.classify(0, 5, 0));
    }

    @Test
    public void downgradedToday_answeredWrongToday() {
        assertEquals(TodayBreakdownUtil.DOWNGRADED_TODAY, TodayBreakdownUtil.classify(0, 0, 4));
    }

    @Test
    public void backlogE_lastAnsweredBeforeToday() {
        assertEquals(TodayBreakdownUtil.BACKLOG_E, TodayBreakdownUtil.classify(0, 1, 2));
        assertEquals(TodayBreakdownUtil.BACKLOG_E, TodayBreakdownUtil.classify(0, 120, 7));
    }

    @Test
    public void overdueAndFuture_areNotInToday() {
        assertEquals(TodayBreakdownUtil.NONE, TodayBreakdownUtil.classify(7, 9, 3));    // 逾期 2 天
        assertEquals(TodayBreakdownUtil.NONE, TodayBreakdownUtil.classify(7, 2, 3));    // 还有 5 天
        assertEquals(TodayBreakdownUtil.NONE, TodayBreakdownUtil.classify(30, 1, 1));
    }
}
