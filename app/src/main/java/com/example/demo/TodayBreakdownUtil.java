package com.example.demo;

/**
 * 今柱（第 0 格）的构成拆分：只解释「今天到期的这几道都是什么来路」，不影响分桶与计数。
 * DUE：以前做过、间隔刚好走到今天；NEW：E 级且从未作答（刚添加的新题）；
 * DOWNGRADED：E 级且最近一次作答就在今天（今天答「不会」降下来的）；
 * BACKLOG：E 级但最近作答早于今天（之前就降成 E、一直没再动）。
 * 只做纯计算，方便 JVM 单测；文案在 MainActivity 里拼。
 */
public final class TodayBreakdownUtil {

    public static final int DUE = 0;
    public static final int NEW_NEVER_ANSWERED = 1;
    public static final int DOWNGRADED_TODAY = 2;
    public static final int BACKLOG_E = 3;
    public static final int NONE = -1;
    public static final int KIND_COUNT = 4;

    private TodayBreakdownUtil() {
    }

    /**
     * @param intervalDays 等级间隔（E=0），
     * @param daysSince    距上次作答的自然日数（新题按创建时间算），
     * @param encounterCount 累计作答次数，0 表示从未做过
     * @return 四个类别之一，逾期或未到期返回 {@link #NONE}
     */
    public static int classify(int intervalDays, long daysSince, int encounterCount) {
        if (intervalDays <= 0) {
            if (encounterCount <= 0) return NEW_NEVER_ANSWERED;
            return daysSince <= 0 ? DOWNGRADED_TODAY : BACKLOG_E;
        }
        long left = intervalDays - daysSince;
        if (left == 0) return DUE;
        return NONE;
    }
}
