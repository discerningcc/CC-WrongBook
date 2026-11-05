package com.example.demo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次复习会话：题目顺序、每题结果、断点位置。
 * ACTIVE 表示还能继续做；FINISHED / ABANDONED 表示已归档为复习记录。
 * 序列化后存在 review_history/sessions.json，与题库 JSON 完全分开。
 */
public class ReviewSession implements Serializable {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_FINISHED = "FINISHED";
    public static final String STATUS_ABANDONED = "ABANDONED";

    public static final String RESULT_KNOW = "KNOW";
    public static final String RESULT_VAGUE = "VAGUE";
    public static final String RESULT_FORGET = "FORGET";
    public static final String RESULT_SKIP = "SKIP";
    public static final String RESULT_EXCLUDED = "EXCLUDED"; // 斩题：不计完成数、不回炉重做

    public String id;
    public String bankId;
    public String bankName;      // 题库名快照：题库改名或删除后记录仍能显示
    public long startTime;
    public long endTime;         // 0 表示尚未结束
    public int currentIndex;     // 下一个待答题在 items 中的下标
    public boolean answerShown;  // 当前题的答案是否已展开（恢复时还原）
    public String status;
    public List<Item> items = new ArrayList<>();

    /** 会话中的一道题 */
    public static class Item implements Serializable {
        public String questionId;
        public String result;          // null 表示尚未作答
        public long answeredAt;
        public String contentSnapshot; // 题目内容快照：原题被删后仍能辨认
        public boolean hadImages;
    }

    /** 已处理过的题数（含跳过） */
    public int answeredCount() {
        if (items == null) return 0;
        int n = 0;
        for (Item it : items) {
            if (it.result != null) n++;
        }
        return n;
    }

    public int countOfResult(String result) {
        if (items == null || result == null) return 0;
        int n = 0;
        for (Item it : items) {
            if (result.equals(it.result)) n++;
        }
        return n;
    }

    public int skippedCount() {
        return countOfResult(RESULT_SKIP);
    }

    /** 剩余未处理的题数 */
    public int remainingCount() {
        if (items == null) return 0;
        return Math.max(0, items.size() - currentIndex);
    }

    /** 最后一次作答时间，用于归档时补 endTime */
    public long lastAnsweredAt() {
        long last = 0;
        if (items != null) {
            for (Item it : items) {
                if (it.answeredAt > last) last = it.answeredAt;
            }
        }
        return last;
    }

    public boolean isFinished() {
        return STATUS_FINISHED.equals(status);
    }
}
