package com.example.demo;

/**
 * 今柱（第 0 格）的构成拆分：按「什么时候进来的、最近一次作答是什么时候」来分来路。
 * DUE：有间隔的题，正好今天到期；
 * NEW_TODAY：E 级、今天才添加、今天还没作答（当天进来的新题）；
 * DOWNGRADED_TODAY：E 级、今天作答过（答「不会」，或「模糊」后仍停在 E，即今天新冒出来的不会题）；
 * OLD_DEBT_E：E 级、添加与最近作答都早于今天——加进来当天没做完，一路拖成了旧债。
 * 已斩不计；逾期（有间隔且 left &lt; 0）不进今柱，只体现在主页「到期 N 道」。
 * 只做纯计算，方便 JVM 单测；文案在 MainActivity 里拼。
 */
public final class TodayBreakdownUtil {

    public static final int DUE = 0;
    public static final int NEW_TODAY = 1;
    public static final int DOWNGRADED_TODAY = 2;
    public static final int OLD_DEBT_E = 3;
    public static final int NONE = -1;
    public static final int KIND_COUNT = 4;

    private TodayBreakdownUtil() {
    }

    /**
     * @param intervalDays   等级间隔（E=0）
     * @param daysSince      距上次作答的自然日数（新题按添加时间算）
     * @param encounterCount 累计作答次数，0 表示从未做过
     * @param addedDay       添加日的自然日序号（由题目 id 里的 yyyyMMdd 解析，解析不出传 -1）
     * @param todayDay       今天的自然日序号
     * @return 四个类别之一；逾期、未到期、已斩返回 {@link #NONE}
     */
    public static int classify(int intervalDays, long daysSince, int encounterCount,
                               long addedDay, long todayDay) {
        if (intervalDays <= 0) {
            if (encounterCount > 0 && daysSince <= 0) return DOWNGRADED_TODAY;
            if (encounterCount <= 0 && addedDay >= todayDay) return NEW_TODAY;
            return OLD_DEBT_E;
        }
        long left = intervalDays - daysSince;
        if (left == 0) return DUE;
        return NONE;
    }
}
