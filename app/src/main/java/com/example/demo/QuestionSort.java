package com.example.demo;

import java.util.Calendar;

/**
 * 题目列表排序口径：三种主键（添加时间 / 到期时间 / 更新时间）× 正反 = 六个选项。
 * 这里只放纯计算，比较器在 QuestionBankActivity 里组装，方便 JVM 单测。
 */
public final class QuestionSort {

    /** 主键：0 添加时间，1 到期时间，2 更新时间 */
    public static final int KEY_ADDED = 0;
    public static final int KEY_DUE = 1;
    public static final int KEY_UPDATED = 2;

    public static final int ADDED_DESC = 0;   // 添加时间 · 新→旧（默认）
    public static final int ADDED_ASC = 1;    // 添加时间 · 旧→新
    public static final int DUE_ASC = 2;      // 到期时间 · 近→远（逾期最多的在前）
    public static final int DUE_DESC = 3;     // 到期时间 · 远→近
    public static final int UPDATED_DESC = 4; // 更新时间 · 新→旧
    public static final int UPDATED_ASC = 5;  // 更新时间 · 旧→新
    public static final int OPTION_COUNT = 6;

    public static final String[] LABELS = {
            "添加时间 · 新→旧",
            "添加时间 · 旧→新",
            "到期时间 · 近→远",
            "到期时间 · 远→近",
            "更新时间 · 新→旧",
            "更新时间 · 旧→新"
    };

    /** 标题栏上显示的短标签 */
    public static final String[] SHORT_LABELS = {
            "添加 · 新→旧",
            "添加 · 旧→新",
            "到期 · 近→远",
            "到期 · 远→近",
            "更新 · 新→旧",
            "更新 · 旧→新"
    };

    private QuestionSort() {
    }

    /** 越界或脏数据一律回到默认 */
    public static int clamp(int option) {
        return (option >= 0 && option < OPTION_COUNT) ? option : ADDED_DESC;
    }

    public static String labelOf(int option) {
        return LABELS[clamp(option)];
    }

    public static String shortLabelOf(int option) {
        return SHORT_LABELS[clamp(option)];
    }

    public static int keyOf(int option) {
        return clamp(option) / 2;
    }

    /** 旧→新 / 近→远 / 旧→新 这三个选项取升序，其余降序 */
    public static boolean ascending(int option) {
        int o = clamp(option);
        return o == ADDED_ASC || o == DUE_ASC || o == UPDATED_ASC;
    }

    /**
     * 排序分段：到期排序时已斩题（不用再复习）整体挪到末尾，避免插在待复习中间；
     * 其它排序不分段，全部混排。
     */
    public static int bucket(int option, boolean excluded) {
        return (keyOf(option) == KEY_DUE && excluded) ? 1 : 0;
    }

    /** 到期排序主键：剩余天数，逾期为负；E 级没有间隔，恒为 0（并列后按添加时间兜底） */
    public static long leftDays(int intervalDays, long daysSince) {
        if (intervalDays <= 0) return 0;
        return intervalDays - daysSince;
    }

    /**
     * 从题目 id 解析添加时间（毫秒）：id 形如 20260507_182423_893。
     * 格式不认识返回 -1，排序时当最旧处理。
     */
    public static long addedMillis(String id) {
        if (id == null || id.length() < 19) return -1;
        if (id.charAt(8) != '_' || id.charAt(15) != '_') return -1;
        for (int i = 0; i < 19; i++) {
            if (i == 8 || i == 15) continue;
            char c = id.charAt(i);
            if (c < '0' || c > '9') return -1;
        }
        int year = Integer.parseInt(id.substring(0, 4));
        int month = Integer.parseInt(id.substring(4, 6));
        int day = Integer.parseInt(id.substring(6, 8));
        int hour = Integer.parseInt(id.substring(9, 11));
        int minute = Integer.parseInt(id.substring(11, 13));
        int second = Integer.parseInt(id.substring(13, 15));
        int millis = Integer.parseInt(id.substring(16, 19));
        if (year < 1970 || month < 1 || month > 12 || day < 1 || day > 31
                || hour > 23 || minute > 59 || second > 59) {
            return -1;
        }
        Calendar cal = Calendar.getInstance();
        cal.clear();
        cal.set(year, month - 1, day, hour, minute, second);
        cal.set(Calendar.MILLISECOND, millis);
        // 回读校验，防 2 月 30 日这类非法日期被顺延
        if (cal.get(Calendar.MONTH) != month - 1 || cal.get(Calendar.DAY_OF_MONTH) != day) {
            return -1;
        }
        return cal.getTimeInMillis();
    }
}
