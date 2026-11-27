package com.example.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 列表排序口径单测：选项映射、正反方向、到期键、已斩分段、id 时间解析 */
public class QuestionSortTest {

    @Test
    public void options_mapToExpectedKeyAndDirection() {
        assertEquals(QuestionSort.KEY_ADDED, QuestionSort.keyOf(QuestionSort.ADDED_DESC));
        assertEquals(QuestionSort.KEY_ADDED, QuestionSort.keyOf(QuestionSort.ADDED_ASC));
        assertEquals(QuestionSort.KEY_DUE, QuestionSort.keyOf(QuestionSort.DUE_ASC));
        assertEquals(QuestionSort.KEY_DUE, QuestionSort.keyOf(QuestionSort.DUE_DESC));
        assertEquals(QuestionSort.KEY_UPDATED, QuestionSort.keyOf(QuestionSort.UPDATED_DESC));
        assertEquals(QuestionSort.KEY_UPDATED, QuestionSort.keyOf(QuestionSort.UPDATED_ASC));

        assertFalse(QuestionSort.ascending(QuestionSort.ADDED_DESC));
        assertTrue(QuestionSort.ascending(QuestionSort.ADDED_ASC));
        assertTrue(QuestionSort.ascending(QuestionSort.DUE_ASC));
        assertFalse(QuestionSort.ascending(QuestionSort.DUE_DESC));
        assertFalse(QuestionSort.ascending(QuestionSort.UPDATED_DESC));
        assertTrue(QuestionSort.ascending(QuestionSort.UPDATED_ASC));
    }

    @Test
    public void outOfRangeOptionFallsBackToDefault() {
        assertEquals(QuestionSort.ADDED_DESC, QuestionSort.clamp(-3));
        assertEquals(QuestionSort.ADDED_DESC, QuestionSort.clamp(99));
        assertEquals("添加时间 · 新→旧", QuestionSort.labelOf(-1));
        assertEquals("更新 · 旧→新", QuestionSort.shortLabelOf(QuestionSort.UPDATED_ASC));
    }

    @Test
    public void excludedGoesToTailOnlyForDueSort() {
        assertEquals(1, QuestionSort.bucket(QuestionSort.DUE_ASC, true));
        assertEquals(0, QuestionSort.bucket(QuestionSort.DUE_ASC, false));
        // 其它排序不分段，斩题照常参与排序
        assertEquals(0, QuestionSort.bucket(QuestionSort.ADDED_DESC, true));
        assertEquals(0, QuestionSort.bucket(QuestionSort.UPDATED_DESC, true));
    }

    @Test
    public void leftDaysKeepsEZeroAndAllowsNegativeOverdue() {
        // E 级 interval=0：恒为 0，并列后按添加时间兜底
        assertEquals(0, QuestionSort.leftDays(0, 0));
        assertEquals(0, QuestionSort.leftDays(0, 140));
        // D 级间隔 1 天：逾期按负数，越逾期越小
        assertEquals(1, QuestionSort.leftDays(1, 0));
        assertEquals(0, QuestionSort.leftDays(1, 1));
        assertEquals(-10, QuestionSort.leftDays(1, 11));
        // S 级间隔 30 天
        assertEquals(30, QuestionSort.leftDays(30, 0));
        assertEquals(-5, QuestionSort.leftDays(30, 35));
    }

    @Test
    public void addedMillisParsesIdAndKeepsOrder() {
        long a = QuestionSort.addedMillis("20261126_165706_783");
        long b = QuestionSort.addedMillis("20261126_165707_000");
        long c = QuestionSort.addedMillis("20261127_090000_001");
        assertTrue(a > 0);
        assertTrue(b > a);
        assertTrue(c > b);
        // 同一秒内靠毫秒拉开
        long s1 = QuestionSort.addedMillis("20261126_165706_100");
        long s2 = QuestionSort.addedMillis("20261126_165706_900");
        assertTrue(s2 > s1);
    }

    @Test
    public void addedMillisRejectsMalformedIds() {
        assertEquals(-1, QuestionSort.addedMillis(null));
        assertEquals(-1, QuestionSort.addedMillis(""));
        assertEquals(-1, QuestionSort.addedMillis("20261126"));
        assertEquals(-1, QuestionSort.addedMillis("2026_11_26_1"));
        assertEquals(-1, QuestionSort.addedMillis("abcd1126_165706_783"));
        // 2 月 30 日不存在，不允许被顺延成 3 月
        assertEquals(-1, QuestionSort.addedMillis("20260230_120000_000"));
        assertEquals(-1, QuestionSort.addedMillis("20261301_120000_000"));
        assertEquals(-1, QuestionSort.addedMillis("20261126_246000_000"));
    }
}
