package com.example.demo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 未来 6 格分桶与柱高归一单测：今天/逾期不进未来格、E 级交给今柱、7 天窗口截断、斩题排除、平方根压缩。
 */
public class DueBucketUtilTest {

    @Test
    public void bucketOf_eLevelNeverInFuture() {
        assertEquals(-1, DueBucketUtil.bucketOf(0, 0, false));    // E 级恒算今天，由 TodayBreakdownUtil 判定
        assertEquals(-1, DueBucketUtil.bucketOf(0, 12, false));
    }

    @Test
    public void bucketOf_todayIsNotInFuture() {
        assertEquals(-1, DueBucketUtil.bucketOf(1, 1, false));    // D 级：隔一天 → 正好今天到期
        assertEquals(-1, DueBucketUtil.bucketOf(30, 30, false));  // S 级：第 30 天当天到期
    }

    @Test
    public void bucketOf_overdueIsNotCounted() {
        assertEquals(-1, DueBucketUtil.bucketOf(30, 40, false)); // 逾期 10 天，不并进今天
        assertEquals(-1, DueBucketUtil.bucketOf(1, 2, false));
        assertEquals(-1, DueBucketUtil.bucketOf(7, 8, false));
    }

    @Test
    public void bucketOf_insideWindow() {
        assertEquals(1, DueBucketUtil.bucketOf(1, 0, false));      // D 级：今天刚答过，明天到期
        assertEquals(1, DueBucketUtil.bucketOf(3, 2, false));
        assertEquals(3, DueBucketUtil.bucketOf(3, 0, false));
        assertEquals(6, DueBucketUtil.bucketOf(7, 1, false));
        assertEquals(6, DueBucketUtil.bucketOf(30, 24, false));
    }

    @Test
    public void bucketOf_outsideWindowReturnsMinusOne() {
        assertEquals(-1, DueBucketUtil.bucketOf(7, 0, false));   // 第 7 天到期，已在窗口外
        assertEquals(-1, DueBucketUtil.bucketOf(30, 0, false));
    }

    @Test
    public void bucketOf_excludedIsNeverCounted() {
        assertEquals(-1, DueBucketUtil.bucketOf(0, 0, true));
        assertEquals(-1, DueBucketUtil.bucketOf(1, 3, true));
    }

    @Test
    public void heightFraction_sqrtAndCaps() {
        assertEquals(0f, DueBucketUtil.heightFraction(0, 157), 0.0001f);
        assertEquals(1f, DueBucketUtil.heightFraction(157, 157), 0.0001f);
        assertEquals(1f, DueBucketUtil.heightFraction(200, 157), 0.0001f);   // 只用比例，不越界
        // 4/157 -> sqrt ≈ 0.1596：线性只剩 2.5%，平方根才看得见
        assertEquals(0.1596f, DueBucketUtil.heightFraction(4, 157), 0.001f);
        assertTrue(DueBucketUtil.heightFraction(1, 157) > 0.07f);
        assertTrue(DueBucketUtil.heightFraction(4, 157) > DueBucketUtil.heightFraction(1, 157));
    }

    @Test
    public void heightFraction_zeroMaxDegradesGracefully() {
        assertEquals(0f, DueBucketUtil.heightFraction(3, 0), 0.0001f);
    }
}
