package com.example.demo;

/**
 * 「未来 7 天到期」分桶规则：第 0 格 = 今天（含已逾期），第 1..6 格 = 明天 .. 第 6 天。
 * 与 QuestionStorage.isDue 同一口径：已斩题不计，E 级（间隔 0）恒算今天。
 * 只做纯计算，方便 JVM 单测；绘制在 DueChartView 里。
 */
public final class DueBucketUtil {

    public static final int BUCKET_COUNT = 7;

    private DueBucketUtil() {
    }

    /**
     * 该题落在第几格：0 = 今天（到期或逾期），1..6 = 未来第 N 天，-1 = 不在 7 天窗口。
     */
    public static int bucketOf(int intervalDays, long daysSince, boolean excluded) {
        if (excluded) return -1;
        if (intervalDays <= 0) return 0;
        long left = intervalDays - daysSince;
        if (left <= 0) return 0;
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
