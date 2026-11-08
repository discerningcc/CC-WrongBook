package com.example.demo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** 每题在同一次会话内最多重做几次（首遍之外的副本数）；1 = 首遍不会只追加 1 份队尾副本，副本再不会不再追加 */
    public static final int REDO_CAP = 1;

    public String id;
    public String bankId;
    public String bankName;      // 题库名快照：题库改名或删除后记录仍能显示
    public long startTime;
    public long endTime;         // 0 表示尚未结束
    public int currentIndex;     // 下一个待答题在 items 中的下标
    public boolean answerShown;  // 当前题的答案是否已展开（恢复时还原）
    public String status;
    public List<Item> items = new ArrayList<>();
    /** 会话创建时的原题数量：重做副本不计入进度分母 */
    public int originalCount;

    /** 本次复习累计总时长（毫秒）；旧记录缺字段默认 0 */
    public long totalElapsedMs;
    /** 用户手动暂停计时中（点时间行切换） */
    public boolean timerPaused;

    /** 会话中的一道题 */
    public static class Item implements Serializable {
        public String questionId;
        public String result;          // null 表示尚未作答
        public long answeredAt;
        public String contentSnapshot; // 题目内容快照：原题被删后仍能辨认
        public boolean hadImages;
        public boolean redo;           // true = 答错后追加的重做副本
        public long dwellMs;           // 本题停留时长（毫秒）；旧记录缺字段默认 0
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

    /**
     * 该次作答是否计入题目累计次数：
     * 记得 / 模糊 / 不会计入；跳过、斩题不计；答错追加的重做副本不计。
     */
    public static boolean countsEncounter(String result, boolean redo) {
        if (redo) return false;
        return RESULT_KNOW.equals(result) || RESULT_VAGUE.equals(result) || RESULT_FORGET.equals(result);
    }

    /** 剩余未处理的题数 */
    public int remainingCount() {
        if (items == null) return 0;
        return Math.max(0, items.size() - currentIndex);
    }

    /**
     * 原题总数：进度分母。旧版本会话没有 originalCount 字段时退回 items 数。
     */
    public int originalTotal() {
        return originalCount > 0 ? originalCount : items.size();
    }

    /**
     * 已完成的原题数（去重）：
     * · 任一遍记得 / 模糊 / 跳过 / 斩题 → 算完成；
     * · 一直不会但已用尽重做次数（达到上限）→ 也算完成，不再挂起；
     * · 仍有重做副本待答的不会题 → 未完成。
     */
    public int closedOriginalCount() {
        Set<String> all = new HashSet<>();
        Set<String> closed = new HashSet<>();
        Map<String, Integer> forgetOcc = new HashMap<>();
        if (items != null) {
            for (Item it : items) {
                if (it.questionId == null) continue;
                all.add(it.questionId);
                if (RESULT_KNOW.equals(it.result) || RESULT_VAGUE.equals(it.result)
                        || RESULT_SKIP.equals(it.result) || RESULT_EXCLUDED.equals(it.result)) {
                    closed.add(it.questionId);
                } else if (RESULT_FORGET.equals(it.result)) {
                    forgetOcc.merge(it.questionId, 1, Integer::sum);
                }
            }
        }
        int n = 0;
        for (String id : all) {
            if (closed.contains(id)) {
                n++;
            } else if (forgetOcc.getOrDefault(id, 0) >= 1 + REDO_CAP) {
                n++;
            }
        }
        return n;
    }

    /**
     * 按原题去重后的结果统计，顺序：记得、模糊、不会、跳过、斩题。
     * 同一题有多遍记录时按最优归属：记得 > 模糊 > 跳过 > 斩题 > 不会。
     */
    public int[] uniqueStats() {
        Map<String, String> best = new LinkedHashMap<>();
        if (items != null) {
            for (Item it : items) {
                if (it.questionId == null) continue;
                best.put(it.questionId, betterResult(best.get(it.questionId), it.result));
            }
        }
        int[] r = new int[5];
        for (String res : best.values()) {
            if (RESULT_KNOW.equals(res)) r[0]++;
            else if (RESULT_VAGUE.equals(res)) r[1]++;
            else if (RESULT_FORGET.equals(res)) r[2]++;
            else if (RESULT_SKIP.equals(res)) r[3]++;
            else if (RESULT_EXCLUDED.equals(res)) r[4]++;
        }
        return r;
    }

    private static String betterResult(String a, String b) {
        if (b == null) return a;
        if (a == null) return b;
        if (RESULT_KNOW.equals(a)) return a;
        if (RESULT_KNOW.equals(b)) return b;
        if (RESULT_VAGUE.equals(a)) return a;
        if (RESULT_VAGUE.equals(b)) return b;
        if (RESULT_SKIP.equals(a)) return a;
        if (RESULT_SKIP.equals(b)) return b;
        if (RESULT_EXCLUDED.equals(a)) return a;
        if (RESULT_EXCLUDED.equals(b)) return b;
        return RESULT_FORGET;
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
