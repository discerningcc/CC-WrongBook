package com.example.demo;

/**
 * 今柱（第 0 格）的构成：只数「今天新产生的功课」，三类加起来就是柱高。
 * DUE_TODAY：有间隔的题，正好今天到期（今日到期）；
 * ADDED_TODAY：E 级、今天才添加、还没作答（今日添加）；
 * NOT_PASSED_TODAY：E 级、今天作答过——从任何等级答「不会」降到 E，或 E 级点「模糊」仍停在 E（今日未过）。
 * 其余一律不上柱：旧债 E（添加当天没做完、一路拖成欠账的）、其他等级逾期，都只体现在主页「到期 N 道」。
 * 已斩不计。只做纯计算，方便 JVM 单测；文案在 MainActivity 里拼。
 */
public final class TodayBreakdownUtil {

    public static final int DUE_TODAY = 0;
    public static final int ADDED_TODAY = 1;
    public static final int NOT_PASSED_TODAY = 2;
    public static final int NONE = -1;
    public static final int KIND_COUNT = 3;

    private TodayBreakdownUtil() {
    }

    /**
     * @param intervalDays   等级间隔（E=0）
     * @param daysSince      距上次作答的自然日数（新题按添加时间算）
     * @param encounterCount 累计作答次数，0 表示从未做过
     * @param addedDay       添加日的自然日序号（由题目 id 里的 yyyyMMdd 解析，解析不出传 -1）
     * @param todayDay       今天的自然日序号
     * @return 三个类别之一；旧债 E、逾期、未到期、已斩返回 {@link #NONE}
     */
    public static int classify(int intervalDays, long daysSince, int encounterCount,
                               long addedDay, long todayDay) {
        if (intervalDays <= 0) {
            if (encounterCount > 0 && daysSince <= 0) return NOT_PASSED_TODAY;
            if (encounterCount <= 0 && addedDay >= todayDay) return ADDED_TODAY;
            return NONE;   // 旧债 E：不在添加当天完成，算欠账，不上柱
        }
        long left = intervalDays - daysSince;
        if (left == 0) return DUE_TODAY;
        return NONE;       // 逾期与未到期都不进今柱
    }
}
