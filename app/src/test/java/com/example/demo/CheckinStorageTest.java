package com.example.demo;

import org.junit.Test;

import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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

    // ---------- 备份合并 mergeMaps ----------

    private CheckinRecord record(String dateKey, int done, int budget, long checkedAt) {
        CheckinRecord r = new CheckinRecord();
        r.dateKey = dateKey;
        r.dayStart = checkedAt;
        r.done = done;
        r.budget = budget;
        r.checkedAt = checkedAt;
        r.bankName = "数学";
        return r;
    }

    @Test
    public void merge_nullMaps_zero() {
        assertEquals(0, CheckinStorage.mergeMaps(null, null));
        Map<String, CheckinRecord> local = new HashMap<>();
        assertEquals(0, CheckinStorage.mergeMaps(local, null));
        assertEquals(0, CheckinStorage.mergeMaps(null, new HashMap<String, CheckinRecord>()));
    }

    @Test
    public void merge_emptyIncoming_zero() {
        Map<String, CheckinRecord> local = new HashMap<>();
        assertEquals(0, CheckinStorage.mergeMaps(local, new HashMap<String, CheckinRecord>()));
        assertTrue(local.isEmpty());
    }

    @Test
    public void merge_newKeys_addedWithCount() {
        Map<String, CheckinRecord> local = new HashMap<>();
        Map<String, CheckinRecord> incoming = new HashMap<>();
        incoming.put("2026-11-05", record("2026-11-05", 50, 50, 1762300000000L));
        incoming.put("2026-11-06", record("2026-11-06", 0, 0, 1762386400000L));
        assertEquals(2, CheckinStorage.mergeMaps(local, incoming));
        assertEquals(2, local.size());
        assertEquals(50, local.get("2026-11-05").done);
    }

    @Test
    public void merge_existingKey_localNotOverwritten() {
        Map<String, CheckinRecord> local = new HashMap<>();
        local.put("2026-11-05", record("2026-11-05", 10, 50, 1762300000000L));
        Map<String, CheckinRecord> incoming = new HashMap<>();
        incoming.put("2026-11-05", record("2026-11-05", 99, 200, 1762300000000L));
        incoming.put("2026-11-06", record("2026-11-06", 30, 50, 1762386400000L));
        assertEquals(1, CheckinStorage.mergeMaps(local, incoming));
        assertEquals(10, local.get("2026-11-05").done);
        assertEquals(50, local.get("2026-11-05").budget);
    }

    @Test
    public void merge_nullKeyOrValue_skipped() {
        Map<String, CheckinRecord> local = new HashMap<>();
        Map<String, CheckinRecord> incoming = new HashMap<>();
        incoming.put(null, record("2026-11-05", 50, 50, 1762300000000L));
        incoming.put("2026-11-06", null);
        assertEquals(0, CheckinStorage.mergeMaps(local, incoming));
        assertTrue(local.isEmpty());
    }

    @Test
    public void merge_invalidDateKey_skipped() {
        Map<String, CheckinRecord> local = new HashMap<>();
        Map<String, CheckinRecord> incoming = new HashMap<>();
        incoming.put("2026-13-40", record("2026-13-40", 50, 50, 1762300000000L));
        incoming.put("abc", record("abc", 50, 50, 1762300000000L));
        incoming.put("2026-11-05", record("2026-11-05", 50, 50, 1762300000000L));
        assertEquals(1, CheckinStorage.mergeMaps(local, incoming));
        assertTrue(local.containsKey("2026-11-05"));
    }

    @Test
    public void merge_negativeField_skipped() {
        Map<String, CheckinRecord> local = new HashMap<>();
        Map<String, CheckinRecord> incoming = new HashMap<>();
        incoming.put("2026-11-05", record("2026-11-05", -1, 50, 1762300000000L));
        incoming.put("2026-11-06", record("2026-11-06", 30, -2, 1762386400000L));
        incoming.put("2026-11-07", record("2026-11-07", 30, 50, -1L));
        incoming.put("2026-11-08", record("2026-11-08", 30, 50, 1762472800000L));
        assertEquals(1, CheckinStorage.mergeMaps(local, incoming));
        assertTrue(local.containsKey("2026-11-08"));
    }

    @Test
    public void merge_mixed_validAndInvalid_countsOnlyValidNew() {
        Map<String, CheckinRecord> local = new HashMap<>();
        local.put("2026-11-05", record("2026-11-05", 10, 50, 1762300000000L));
        Map<String, CheckinRecord> incoming = new HashMap<>();
        incoming.put("2026-11-05", record("2026-11-05", 99, 200, 1762300000000L)); // 本地已有
        incoming.put("2026-11-06", record("2026-11-06", 30, 50, 1762386400000L)); // 合法新键
        incoming.put("bad-key", record("bad-key", 30, 50, 1762386400000L));             // 非法日期
        incoming.put("2026-11-07", record("2026-11-07", -5, 50, 1762472800000L));       // 负数题数
        assertEquals(1, CheckinStorage.mergeMaps(local, incoming));
        assertEquals(2, local.size());
    }
}
