package com.example.demo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * QuestionStorage 纯静态口径单测：等级间隔、降级顺序、等级颜色。
 * 这些方法不依赖 Context / SharedPreferences，可在宿主机 JVM 直接运行。
 */
public class QuestionStorageTest {

    @Test
    public void intervalDaysOf_knownLevels() {
        assertEquals(1, QuestionStorage.intervalDaysOf("D"));
        assertEquals(3, QuestionStorage.intervalDaysOf("C"));
        assertEquals(7, QuestionStorage.intervalDaysOf("B"));
        assertEquals(15, QuestionStorage.intervalDaysOf("A"));
        assertEquals(QuestionStorage.S_INTERVAL_DAYS, QuestionStorage.intervalDaysOf("S"));
    }

    @Test
    public void intervalDaysOf_eLevelAndUnknownAreZero() {
        assertEquals(0, QuestionStorage.intervalDaysOf("E"));
        assertEquals(0, QuestionStorage.intervalDaysOf(null));
        assertEquals(0, QuestionStorage.intervalDaysOf("X"));
    }

    @Test
    public void lowerLevelOf_stepsDownTheLadder() {
        assertEquals("A", QuestionStorage.lowerLevelOf("S"));
        assertEquals("B", QuestionStorage.lowerLevelOf("A"));
        assertEquals("C", QuestionStorage.lowerLevelOf("B"));
        assertEquals("D", QuestionStorage.lowerLevelOf("C"));
        assertEquals("E", QuestionStorage.lowerLevelOf("D"));
    }

    @Test
    public void lowerLevelOf_nullAndUnknownFallBackToE() {
        assertEquals("E", QuestionStorage.lowerLevelOf(null));
        assertEquals("E", QuestionStorage.lowerLevelOf(""));
        assertEquals("E", QuestionStorage.lowerLevelOf("X"));
    }

    @Test
    public void statusColor_mapsEachLevelToDistinctColor() {
        assertEquals(0xfff44336, QuestionStorage.statusColor("S"));
        assertEquals(0xffff9800, QuestionStorage.statusColor("A"));
        assertEquals(0xff9c27b0, QuestionStorage.statusColor("B"));
        assertEquals(0xff2196f3, QuestionStorage.statusColor("C"));
        assertEquals(0xff4caf50, QuestionStorage.statusColor("D"));
        assertEquals(0xff9e9e9e, QuestionStorage.statusColor("E"));
        // 空值与未知等级都落回 E 的灰色
        assertEquals(0xff9e9e9e, QuestionStorage.statusColor(null));
        assertEquals(0xff9e9e9e, QuestionStorage.statusColor("X"));

        // 五个有颜色的等级互不相同
        assertNotEquals(QuestionStorage.statusColor("S"), QuestionStorage.statusColor("A"));
        assertNotEquals(QuestionStorage.statusColor("A"), QuestionStorage.statusColor("B"));
        assertNotEquals(QuestionStorage.statusColor("B"), QuestionStorage.statusColor("C"));
        assertNotEquals(QuestionStorage.statusColor("C"), QuestionStorage.statusColor("D"));
    }

    @Test
    public void copy_preservesFavoriteAndExcludedFlags() {
        Question q = new Question("题干", "答案");
        assertFalse(q.favorite);
        assertFalse(q.excluded);

        q.favorite = true;
        q.excluded = true;
        Question c = q.copy();

        assertTrue(c.favorite);
        assertTrue(c.excluded);
        // 副本与原对象独立：改副本不回写原对象
        c.favorite = false;
        assertTrue(q.favorite);
    }
}
