package com.example.demo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 今柱构成单测：今日到期 / 今日添加 / 今日未过，以及旧债 E、逾期、未到期一律不上柱。
 */
public class TodayBreakdownUtilTest {

    private static final long TODAY = 20000L;

    @Test
    public void dueToday_intervalLandsExactlyToday() {
        assertEquals(TodayBreakdownUtil.DUE_TODAY, TodayBreakdownUtil.classify(7, 7, 3, TODAY - 20, TODAY));
        assertEquals(TodayBreakdownUtil.DUE_TODAY, TodayBreakdownUtil.classify(1, 1, 1, TODAY - 3, TODAY));
        assertEquals(TodayBreakdownUtil.DUE_TODAY, TodayBreakdownUtil.classify(30, 30, 9, TODAY - 200, TODAY));
    }

    @Test
    public void addedToday_addedTodayNotAnsweredYet() {
        assertEquals(TodayBreakdownUtil.ADDED_TODAY, TodayBreakdownUtil.classify(0, 0, 0, TODAY, TODAY));
        assertEquals(TodayBreakdownUtil.ADDED_TODAY, TodayBreakdownUtil.classify(0, 0, 0, TODAY + 1, TODAY));
    }

    @Test
    public void notPassedToday_answeredTodayAndStillE() {
        // 任何等级点「不会」降成 E，都算今日未过
        assertEquals(TodayBreakdownUtil.NOT_PASSED_TODAY, TodayBreakdownUtil.classify(0, 0, 4, TODAY - 200, TODAY));
        assertEquals(TodayBreakdownUtil.NOT_PASSED_TODAY, TodayBreakdownUtil.classify(0, 0, 1, TODAY, TODAY));
    }

    @Test
    public void oldDebtE_isNotOnTodayBar() {
        // 昨天添加、一次都没做 → 欠账，不上柱
        assertEquals(TodayBreakdownUtil.NONE, TodayBreakdownUtil.classify(0, 1, 0, TODAY - 1, TODAY));
        // 早前答「不会」降 E、之后再没做 → 欠账
        assertEquals(TodayBreakdownUtil.NONE, TodayBreakdownUtil.classify(0, 5, 2, TODAY - 300, TODAY));
        // id 解析不出添加日，按旧题算
        assertEquals(TodayBreakdownUtil.NONE, TodayBreakdownUtil.classify(0, 0, 0, -1, TODAY));
    }

    @Test
    public void overdueAndFuture_areNotInToday() {
        assertEquals(TodayBreakdownUtil.NONE, TodayBreakdownUtil.classify(7, 9, 3, TODAY - 30, TODAY));    // 逾期 2 天
        assertEquals(TodayBreakdownUtil.NONE, TodayBreakdownUtil.classify(7, 2, 3, TODAY - 30, TODAY));    // 还有 5 天
        assertEquals(TodayBreakdownUtil.NONE, TodayBreakdownUtil.classify(30, 1, 1, TODAY - 300, TODAY));
    }

    @Test
    public void kindCountMatchesThreeCategories() {
        assertEquals(3, TodayBreakdownUtil.KIND_COUNT);
    }
}
