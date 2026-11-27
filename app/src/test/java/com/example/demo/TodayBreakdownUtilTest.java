package com.example.demo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 今柱构成拆分单测：新到期、今天新添加、今天新不会、旧债 E，以及逾期/未到期不参与。
 */
public class TodayBreakdownUtilTest {

    private static final long TODAY = 20000L;

    @Test
    public void due_intervalLandsExactlyToday() {
        assertEquals(TodayBreakdownUtil.DUE, TodayBreakdownUtil.classify(7, 7, 3, TODAY - 20, TODAY));
        assertEquals(TodayBreakdownUtil.DUE, TodayBreakdownUtil.classify(1, 1, 1, TODAY - 3, TODAY));
        assertEquals(TodayBreakdownUtil.DUE, TodayBreakdownUtil.classify(30, 30, 9, TODAY - 200, TODAY));
    }

    @Test
    public void newToday_addedTodayNotAnsweredYet() {
        assertEquals(TodayBreakdownUtil.NEW_TODAY, TodayBreakdownUtil.classify(0, 0, 0, TODAY, TODAY));
        assertEquals(TodayBreakdownUtil.NEW_TODAY, TodayBreakdownUtil.classify(0, 0, 0, TODAY + 1, TODAY));
    }

    @Test
    public void downgradedToday_answeredAndStillE() {
        assertEquals(TodayBreakdownUtil.DOWNGRADED_TODAY, TodayBreakdownUtil.classify(0, 0, 4, TODAY - 200, TODAY));
        assertEquals(TodayBreakdownUtil.DOWNGRADED_TODAY, TodayBreakdownUtil.classify(0, 0, 1, TODAY, TODAY));
    }

    @Test
    public void oldDebtE_notFinishedOnAddedDay() {
        // 昨天添加、一次都没做 → 旧债
        assertEquals(TodayBreakdownUtil.OLD_DEBT_E, TodayBreakdownUtil.classify(0, 1, 0, TODAY - 1, TODAY));
        // 早前答「不会」降 E、之后再没做 → 旧债
        assertEquals(TodayBreakdownUtil.OLD_DEBT_E, TodayBreakdownUtil.classify(0, 5, 2, TODAY - 300, TODAY));
        // id 解析不出添加日，按旧题算
        assertEquals(TodayBreakdownUtil.OLD_DEBT_E, TodayBreakdownUtil.classify(0, 0, 0, -1, TODAY));
    }

    @Test
    public void overdueAndFuture_areNotInToday() {
        assertEquals(TodayBreakdownUtil.NONE, TodayBreakdownUtil.classify(7, 9, 3, TODAY - 30, TODAY));    // 逾期 2 天
        assertEquals(TodayBreakdownUtil.NONE, TodayBreakdownUtil.classify(7, 2, 3, TODAY - 30, TODAY));    // 还有 5 天
        assertEquals(TodayBreakdownUtil.NONE, TodayBreakdownUtil.classify(30, 1, 1, TODAY - 300, TODAY));
    }
}
