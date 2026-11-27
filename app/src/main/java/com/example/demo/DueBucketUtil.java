package com.example.demo;

/**
 * 「未来 7 天到期」分桶规则：第 0 格 = 今天到期，第 1..6 格 = 明天 .. 第 6 天。
 * 每格只数「那一天到期的题」，逾期题不计入任何格（逾期只体现在主页「到期 N 道」）。
 * E 级（间隔 0）每天都要抽查，恒算今天到期；已斩题不计。
 * 只做纯计算，方便 JVM 单测；绘制在 DueChartView 里。
 */
public final class DueBucketUtil {

    public static final int BUCKET_COUNT = 7;

    private DueBucketUtil() {
    }

    /**
     * 该题落在第几格：0 = 今天到期，1..6 = 未来第 N 天，-1 = 逾期或不在 7 天窗口。
     * 逾期（left &lt; 0）不再并进今天，保证每格都是「那一天到期」的新题。
     */
    public static int bucketOf(int intervalDays, long daysSince, boolean excluded) {
        if (excluded) return -1;
        if (intervalDays <= 0) return 0;
        long left = intervalDays - daysSince;
        if (left < 0) return -1;
        if (left == 0) return 0;
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
