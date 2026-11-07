package com.example.demo;

import org.junit.Test;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * CheckinStorage 纯函数单测：打卡资格门禁矩阵、连续天数、月统计、日期键格式。
 * 仅使用 JDK 日历与集合，不触碰 Context 与文件系统。
 */
public class CheckinStorageTest {

    private static final long ONE_DAY = 24L * 60 * 60 * 1000;

    // ---------- 资格判定 ----------

    @Test
    public void eligible_gateClosed_naturalDayOff() {
        assertFalse(CheckinStorage.isEligible(false, true, false, 50, 50, 0, false));
    }

    @Test
    public void eligible_gateClosed_checkinOff() {
        assertFalse(CheckinStorage.isEligible(true, false, false, 50, 50, 0, false));
    }

    @Test
    public void eligible_bankEmpty_notEligible() {
        assertFalse(CheckinStorage.isEligible(true, true, true, 50, 50, 0, false));
    }

    @Test
    public void eligible_alreadyChecked_notEligible() {
        assertFalse(CheckinStorage.isEligible(true, true, false, 50, 50, 0, true));
    }

    @Test
    public void eligible_finite_budgetReached_true() {
        assertTrue(CheckinStorage.isEligible(true, true, false, 50, 50, 5, false));
        assertTrue(CheckinStorage.isEligible(true, true, false, 50, 60, 5, false));
    }

    @Test
    public void eligible_finite_budgetNotReached_false() {
        assertFalse(CheckinStorage.isEligible(true, true, false, 50, 49, 5, false));
    }

    @Test
    public void eligible_unlimited_noDue_true() {
        assertTrue(CheckinStorage.isEligible(true, true, false, 0, 3, 0, false));
        assertTrue(CheckinStorage.isEligible(true, true, false, -1, 0, 0, false));
    }

    @Test
    public void eligible_unlimited_stillDue_false() {
        assertFalse(CheckinStorage.isEligible(true, true, false, 0, 3, 2, false));
    }

    // ---------- 连续天数 ----------

    private static long noonToday() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 12);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    @Test
    public void streak_emptySet_zero() {
        assertEquals(0, CheckinStorage.streakDays(new HashSet<String>(), noonToday()));
    }

    @Test
    public void streak_todayAndYesterdayChain_countedBack() {
        long today = noonToday();
        Set<String> keys = new HashSet<>();
        keys.add(CheckinStorage.dateKeyOf(today));
        keys.add(CheckinStorage.dateKeyOf(today - ONE_DAY));
        keys.add(CheckinStorage.dateKeyOf(today - 2 * ONE_DAY));
        assertEquals(3, CheckinStorage.streakDays(keys, today));
    }

    @Test
    public void streak_todayUnchecked_startsFromYesterday() {
        long today = noonToday();
        Set<String> keys = new HashSet<>();
        keys.add(CheckinStorage.dateKeyOf(today - ONE_DAY));
        keys.add(CheckinStorage.dateKeyOf(today - 2 * ONE_DAY));
        assertEquals(2, CheckinStorage.streakDays(keys, today));
    }

    @Test
    public void streak_todayCheckedButYesterdayBroken_one() {
        long today = noonToday();
        Set<String> keys = new HashSet<>();
        keys.add(CheckinStorage.dateKeyOf(today));
        keys.add(CheckinStorage.dateKeyOf(today - 3 * ONE_DAY));
        assertEquals(1, CheckinStorage.streakDays(keys, today));
    }

    @Test
    public void streak_todayAndYesterdayBothUnchecked_zero() {
        long today = noonToday();
        Set<String> keys = new HashSet<>();
        keys.add(CheckinStorage.dateKeyOf(today - 3 * ONE_DAY));
        assertEquals(0, CheckinStorage.streakDays(keys, today));
    }

    // ---------- 月统计 ----------

    @Test
    public void monthCount_countsOnlyMatchingYearMonth() {
        Set<String> keys = new HashSet<>();
        keys.add("2026-11-05");
        keys.add("2026-11-20");
        keys.add("2026-10-30");
        keys.add("2025-11-09");
        assertEquals(2, CheckinStorage.monthCount(keys, 2026, 11));
        assertEquals(1, CheckinStorage.monthCount(keys, 2026, 10));
        assertEquals(1, CheckinStorage.monthCount(keys, 2025, 11));
        assertEquals(0, CheckinStorage.monthCount(keys, 2026, 12));
    }

    @Test
    public void monthCount_nullSet_zero() {
        assertEquals(0, CheckinStorage.monthCount(null, 2026, 11));
    }

    // ---------- 日期键 ----------

    @Test
    public void dateKey_formatIsIsoLocalDay() {
        String key = CheckinStorage.dateKeyOf(System.currentTimeMillis());
        assertTrue(Pattern.matches("^\\d{4}-\\d{2}-\\d{2}$", key));
    }

    @Test
    public void dateKey_normalizesWithinSameDay() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 23);
        c.set(Calendar.MINUTE, 30);
        long lateNight = c.getTimeInMillis();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 30);
        long earlyMorning = c.getTimeInMillis();
        assertEquals(CheckinStorage.dateKeyOf(lateNight), CheckinStorage.dateKeyOf(earlyMorning));
    }
}
