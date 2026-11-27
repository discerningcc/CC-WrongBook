package com.example.demo;

import org.junit.Test;

import java.util.Calendar;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * SessionStorage 纯工具方法单测：时长格式化、内容快照、自然日判定、时间文案。
 * 仅使用 JDK 日历/格式化，不触碰 Context 与文件系统。
 */
public class SessionStorageTest {

    @Test
    public void formatDuration_underOneHour() {
        assertEquals("00:00", SessionStorage.formatDuration(0));
        assertEquals("00:59", SessionStorage.formatDuration(59_999));
        assertEquals("01:01", SessionStorage.formatDuration(61_000));
    }

    @Test
    public void formatDuration_overOneHour() {
        assertEquals("1:00:00", SessionStorage.formatDuration(3_600_000));
        assertEquals("1:01:01", SessionStorage.formatDuration(3_661_000));
    }

    @Test
    public void formatDuration_negativeTreatedAsZero() {
        assertEquals("00:00", SessionStorage.formatDuration(-1));
        assertEquals("00:00", SessionStorage.formatDuration(-100_000));
    }

    @Test
    public void snapshot_nullBecomesEmpty_oneLineAndTrimmed() {
        assertEquals("", SessionStorage.snapshot(null));
        assertEquals("", SessionStorage.snapshot("   \n\t "));
        assertEquals("a b c", SessionStorage.snapshot("a\nb\rc"));
    }

    @Test
    public void snapshot_truncatesAtSixtyCharsWithEllipsis() {
        String shortText = "一二三四五六";
        assertEquals(shortText, SessionStorage.snapshot(shortText));

        String longText = repeat("字", 61);
        String snap = SessionStorage.snapshot(longText);
        assertEquals(61, snap.length()); // 60 字 + 省略号
        assertTrue(snap.endsWith("…"));
        assertEquals(longText.substring(0, 60), snap.substring(0, 60));
    }

    private static String repeat(String unit, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(unit);
        return sb.toString();
    }

    @Test
    public void dayStart_zeroesOutTimeOfDay() {
        long now = System.currentTimeMillis();
        long start = SessionStorage.dayStart(now);
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(start);
        assertEquals(0, c.get(Calendar.HOUR_OF_DAY));
        assertEquals(0, c.get(Calendar.MINUTE));
        assertEquals(0, c.get(Calendar.SECOND));
        assertEquals(0, c.get(Calendar.MILLISECOND));
    }

    @Test
    public void isSameDay_sameDayDifferentHour_true() {
        long now = System.currentTimeMillis();
        long base = SessionStorage.dayStart(now) + 12L * 3600_000; // 当天正午，避开午夜边界
        long later = base + 3_600_000; // +1 小时
        assertTrue(SessionStorage.isSameDay(base, later));
    }

    @Test
    public void isSameDay_nextDay_false() {
        long now = System.currentTimeMillis();
        long base = SessionStorage.dayStart(now) + 12L * 3600_000;
        long nextDay = base - 26L * 3600 * 1000; // 退 26 小时，跨自然日
        assertFalse(SessionStorage.isSameDay(base, nextDay));
    }

    @Test
    public void formatTime_matchesHourMinuteFormat() {
        String text = SessionStorage.formatTime(System.currentTimeMillis());
        assertTrue(Pattern.matches("^\\d{2}:\\d{2}$", text));
    }

    @Test
    public void formatDateHeader_todayAndYesterday() {
        long now = System.currentTimeMillis();
        long oneDay = 24L * 60 * 60 * 1000;
        assertEquals("今天", SessionStorage.formatDateHeader(now));
        assertEquals("昨天", SessionStorage.formatDateHeader(now - oneDay));
    }

    @Test
    public void formatDateHeader_olderUsesMonthDay() {
        long threeDaysAgo = System.currentTimeMillis() - 3L * 24 * 3600 * 1000;
        String header = SessionStorage.formatDateHeader(threeDaysAgo);
        assertFalse("今天".equals(header));
        assertFalse("昨天".equals(header));
        assertTrue(Pattern.matches("^\\d{1,2}月\\d{1,2}日$|^\\d{4}年\\d{1,2}月\\d{1,2}日$", header));
    }

    // ==================== 会话文件名 ====================

    @Test
    public void parseName_readsTimeStatusBankAndId() {
        String name = SessionStorage.nameOf(1764000000000L, "FINISHED",
                "209eba8c-fa7e-471b-a338-3759ec352e99", "20261126_134948_382");
        SessionStorage.NameMeta meta = SessionStorage.parseName(name);
        assertEquals(1764000000000L, meta.startTime);
        assertEquals("FINISHED", meta.status);
        assertEquals("209eba8c-fa7e-471b-a338-3759ec352e99", meta.bankId);
        assertEquals("20261126_134948_382", meta.id);   // 会话 id 自带下划线，不能被切碎
    }

    @Test
    public void parseName_noBankBecomesNone() {
        String name = SessionStorage.nameOf(1234L, "ACTIVE", null, "x");
        assertEquals("1234_ACTIVE_none_x.json", name);
        assertNull(SessionStorage.parseName(name).bankId);
    }

    @Test
    public void parseName_rejectsLegacyAndTempNames() {
        assertNull(SessionStorage.parseName("20261126_134948_382.json"));   // 老格式
        assertNull(SessionStorage.parseName("1234_ACTIVE_bank_x.json.tmp"));
        assertNull(SessionStorage.parseName("1234_ACTIVE_bank_x"));          // 没有扩展名
        assertNull(SessionStorage.parseName(null));
    }
}
