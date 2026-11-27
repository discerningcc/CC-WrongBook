package com.example.demo;

/**
 * 「未来 7 天到期」分桶：第 1..6 格 = 明天 .. 第 6 天到期的新题。
 * 第 0 格（今柱）不归这里管，由 {@link TodayBreakdownUtil} 只数「今日到期 / 今日添加 / 今日未过」三类。
 * 欠账（旧债 E、其他等级逾期）不进任何格，只体现在主页「到期 N 道」。
 * 已斩题不计。只做纯计算，方便 JVM 单测；绘制在 DueChartView 里。
 */
public final class DueBucketUtil {

    public static final int BUCKET_COUNT = 7;

    private DueBucketUtil() {
    }

    /**
     * 该题落在第几格：1..6 = 未来第 N 天到期，-1 = 今天到期、逾期、窗口外或已斩。
     * 今天到期的题由 TodayBreakdownUtil 决定上不上柱，这里一律返回 -1。
     */
    public static int bucketOf(int intervalDays, long daysSince, boolean excluded) {
        if (excluded) return -1;
        if (intervalDays <= 0) return -1;   // E 级恒今天，交给 TodayBreakdownUtil
        long left = intervalDays - daysSince;
        if (left <= 0) return -1;           // 今天到期或逾期，都不在未来格里
        if (left >= BUCKET_COUNT) return -1;
        return (int) left;
    }

    /**
     * 柱高比例（0..1）：用平方根压缩。
     * 「今天 157 道、明天 2 道」这种悬殊分布，线性归一会把后几格压成一条线。
     */
    public static float heightFraction(int count, int maxCount) {
        if (count <= 0 || maxCount <= 0) return 0f;
        if (count >= maxCount) return 1f;
        return (float) Math.sqrt((double) count / (double) maxCount);
    }
}
