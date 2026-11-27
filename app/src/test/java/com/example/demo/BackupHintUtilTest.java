package com.example.demo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 备份提示单测：30 天阈值、新题库 7 天豁免、空库不提示、文案分档。
 */
public class BackupHintUtilTest {

    @Test
    public void stateOf_emptyBankNeverHints() {
        assertEquals(BackupHintUtil.HIDE, BackupHintUtil.stateOf(false, 0, 999, 0));
        assertEquals(BackupHintUtil.HIDE, BackupHintUtil.stateOf(true, 999, 999, 0));
    }

    @Test
    public void stateOf_noRecordWaitsForBankAge() {
        assertEquals(BackupHintUtil.HIDE, BackupHintUtil.stateOf(false, 0, 3, 184));
        assertEquals(BackupHintUtil.EMPHASIS, BackupHintUtil.stateOf(false, 0, 7, 184));
        assertEquals(BackupHintUtil.EMPHASIS, BackupHintUtil.stateOf(false, 0, 8, 184));
    }

    @Test
    public void stateOf_staleThresholdIsThirtyDays() {
        assertEquals(BackupHintUtil.NORMAL, BackupHintUtil.stateOf(true, 0, 999, 184));
        assertEquals(BackupHintUtil.NORMAL, BackupHintUtil.stateOf(true, 29, 999, 184));
        assertEquals(BackupHintUtil.EMPHASIS, BackupHintUtil.stateOf(true, 30, 999, 184));
        assertEquals(BackupHintUtil.EMPHASIS, BackupHintUtil.stateOf(true, 45, 999, 184));
    }

    @Test
    public void textOf_recentDays() {
        assertEquals("上次备份：今天", BackupHintUtil.textOf(true, 0));
        assertEquals("上次备份：昨天", BackupHintUtil.textOf(true, 1));
        assertEquals("上次备份：2 天前", BackupHintUtil.textOf(true, 2));
        assertEquals("上次备份：29 天前", BackupHintUtil.textOf(true, 29));
    }

    @Test
    public void textOf_staleAndMissing() {
        assertEquals("已 30 天没备份 · 点这里导出", BackupHintUtil.textOf(true, 30));
        assertEquals("已 45 天没备份 · 点这里导出", BackupHintUtil.textOf(true, 45));
        assertEquals("还没备份过 · 点这里导出", BackupHintUtil.textOf(false, 0));
    }

    @Test
    public void textOf_futureTimestampClampsToToday() {
        assertEquals("上次备份：今天", BackupHintUtil.textOf(true, -3));   // 用户改过系统时间
    }
}
