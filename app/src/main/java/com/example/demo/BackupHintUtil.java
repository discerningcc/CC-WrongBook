package com.example.demo;

/**
 * 主页「上次导出备份」提示的判定：是否显示、用什么颜色、写什么文案。
 * 时间一律按自然日差（跨零点即 +1），不用 24 小时，避免隔夜之后还显示「今天」。
 * 纯函数，方便 JVM 单测。
 */
public final class BackupHintUtil {

    /** 超过这么多天没备份，换成主色强调 */
    public static final long STALE_DAYS = 30;
    /** 从未备份时，题库起码存在这么多天才开始提醒（新装用户不打扰） */
    public static final long NEW_BANK_GRACE_DAYS = 7;

    public static final int HIDE = 0;
    public static final int NORMAL = 1;
    public static final int EMPHASIS = 2;

    private BackupHintUtil() {
    }

    /**
     * @param hasRecord        本机是否记录过导出
     * @param daysSinceExport  距上次导出的自然日差（hasRecord=false 时忽略）
     * @param bankAgeDays      当前题库创建至今的自然日差
     * @param questionCount    当前题库题数
     */
    public static int stateOf(boolean hasRecord, long daysSinceExport, long bankAgeDays, int questionCount) {
        if (questionCount <= 0) return HIDE;
        if (!hasRecord) {
            return bankAgeDays >= NEW_BANK_GRACE_DAYS ? EMPHASIS : HIDE;
        }
        return daysSinceExport >= STALE_DAYS ? EMPHASIS : NORMAL;
    }

    /** 提示文案；hasRecord=false 时 daysSinceExport 不参与 */
    public static String textOf(boolean hasRecord, long daysSinceExport) {
        if (!hasRecord) return "还没备份过 · 点这里导出";
        long days = Math.max(0, daysSinceExport);
        if (days == 0) return "上次备份：今天";
        if (days == 1) return "上次备份：昨天";
        if (days >= STALE_DAYS) return "已 " + days + " 天没备份 · 点这里导出";
        return "上次备份：" + days + " 天前";
    }
}
