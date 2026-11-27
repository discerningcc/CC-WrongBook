package com.example.demo;

import org.junit.Test;

import java.io.File;

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
        q.encounterCount = 3;
        Question c = q.copy();

        assertTrue(c.favorite);
        assertTrue(c.excluded);
        assertEquals(3, c.encounterCount);
        // 副本与原对象独立：改副本不回写原对象
        c.favorite = false;
        c.encounterCount = 0;
        assertTrue(q.favorite);
        assertEquals(3, q.encounterCount);
    }

    @Test
    public void nextTodayDone_sameDayAccumulates() {
        assertEquals(6, QuestionStorage.nextTodayDone(100, 5, 100));
        assertEquals(1, QuestionStorage.nextTodayDone(100, 0, 100));
    }

    @Test
    public void nextTodayDone_crossDayResetsToOne() {
        // 跨自然日：昨日残留不计入，从 1 重新起算
        assertEquals(1, QuestionStorage.nextTodayDone(99, 32, 100));
        // 首次使用（无记录日戳 -1）同样从 1 起算
        assertEquals(1, QuestionStorage.nextTodayDone(-1, 0, 100));
    }

    // ==================== 解压路径校验（Zip Slip） ====================

    @Test
    public void isInsideDir_acceptsNormalEntries() {
        File dir = tempDir("normal");
        assertTrue(QuestionStorage.isInsideDir(dir, new File(dir, "questions.json")));
        assertTrue(QuestionStorage.isInsideDir(dir, new File(dir, "images/a.jpg")));
        assertTrue(QuestionStorage.isInsideDir(dir, new File(dir, "images/2026/11/a.jpg")));
    }

    @Test
    public void isInsideDir_rejectsParentTraversal() {
        File dir = tempDir("traversal");
        assertFalse(QuestionStorage.isInsideDir(dir, new File(dir, "../evil.json")));
        assertFalse(QuestionStorage.isInsideDir(dir, new File(dir, "images/../../evil.json")));
        assertFalse(QuestionStorage.isInsideDir(dir, new File(dir, "..")));
    }

    @Test
    public void isInsideDir_rejectsSiblingWithSamePrefix() {
        File dir = tempDir("prefix");
        File sibling = new File(dir.getParentFile(), dir.getName() + "_evil/x.json");
        assertFalse(QuestionStorage.isInsideDir(dir, sibling));
    }

    /** 每个用例一个独立临时目录，避免相互干扰 */
    private static File tempDir(String tag) {
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "zipslip_" + tag + "_" + System.nanoTime());
        assertTrue(dir.isDirectory() || dir.mkdirs());
        return dir;
    }
}
