package com.example.demo;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 复习记录页：按自然日分组展示历史会话。
 * 最近 7 天照常展开，更早的日期默认折叠成一行，点开才显示。
 * 点一条会话展开题目清单，点某道题跳原题详情（仅限当前题库的题）。
 */
public class ReviewHistoryActivity extends AppCompatActivity {

    /** 这个天数以内（含今天）的日期默认展开，更早的折叠 */
    private static final int EXPAND_DAYS = 7;
    private static final String KEY_EXPANDED_DAYS = "expanded_days";
    private static final String KEY_EXPANDED_SESSIONS = "expanded_sessions";

    private LinearLayout container;
    /** 已展开的日期分组（dayStart 字符串）；旋转重建后据此恢复 */
    private final Set<String> expandedDays = new HashSet<>();
    /** 已展开的会话卡片（会话 id）；旋转重建后据此恢复 */
    private final Set<String> expandedSessions = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_review_history);
        container = findViewById(R.id.history_container);
        findViewById(R.id.btn_history_back).setOnClickListener(v -> finish());
        if (savedInstanceState != null) {
            List<String> days = savedInstanceState.getStringArrayList(KEY_EXPANDED_DAYS);
            List<String> sessions = savedInstanceState.getStringArrayList(KEY_EXPANDED_SESSIONS);
            if (days != null) expandedDays.addAll(days);
            if (sessions != null) expandedSessions.addAll(sessions);
        }
        loadData();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putStringArrayList(KEY_EXPANDED_DAYS, new ArrayList<>(expandedDays));
        outState.putStringArrayList(KEY_EXPANDED_SESSIONS, new ArrayList<>(expandedSessions));
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadData();
    }

    private void loadData() {
        container.removeAllViews();

        List<ReviewSession> list = SessionStorage.loadAll(this);
        Collections.sort(list, (a, b) -> Long.compare(b.startTime, a.startTime));

        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无复习记录\n\n完成一次复习，或中途退出复习后，这里会留下足迹");
            empty.setTextSize(14);
            empty.setTextColor(0xFF888888);
            empty.setGravity(android.view.Gravity.CENTER);
            empty.setPadding(dp(24), dp(64), dp(24), dp(24));
            container.addView(empty);
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(this);

        // 每个自然日有多少条，折叠标题要显示
        Map<Long, Integer> countPerDay = new HashMap<>();
        for (ReviewSession s : list) {
            long day = SessionStorage.dayStart(s.startTime);
            Integer c = countPerDay.get(day);
            countPerDay.put(day, c == null ? 1 : c + 1);
        }

        long today = SessionStorage.dayStart(System.currentTimeMillis());
        long oneDay = 24L * 60 * 60 * 1000;
        long expandFrom = today - (EXPAND_DAYS - 1) * oneDay;

        String lastHeader = null;
        LinearLayout target = container;
        for (ReviewSession s : list) {
            String header = SessionStorage.formatDateHeader(s.startTime);
            if (!header.equals(lastHeader)) {
                long day = SessionStorage.dayStart(s.startTime);
                int count = countPerDay.containsKey(day) ? countPerDay.get(day) : 1;
                if (day >= expandFrom) {
                    container.addView(createHeader(header));
                    target = container;
                } else {
                    target = addCollapsedGroup(header, count, day, expandedDays.contains(String.valueOf(day)));
                }
                lastHeader = header;
            }
            createSessionCard(inflater, s, target);
        }

        TextView footer = new TextView(this);
        footer.setText("每个题库最多保留最近 100 条记录，更早的会自动清理");
        footer.setTextSize(12);
        footer.setTextColor(0xFFAAAAAA);
        footer.setGravity(android.view.Gravity.CENTER);
        footer.setPadding(dp(16), dp(24), dp(16), dp(24));
        container.addView(footer);
    }

    /** 更早的日期折叠成一行「11月3日 · 3 条 ▸」，点开才显示当天卡片 */
    private LinearLayout addCollapsedGroup(String header, int count, long day, boolean open) {
        String dayKey = String.valueOf(day);
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);

        TextView head = new TextView(this);
        head.setText(header + " · " + count + " 条 " + (open ? "▾" : "▸"));
        head.setTextSize(14);
        head.setTextColor(0xFF888888);
        head.setPadding(dp(16), dp(16), dp(16), dp(6));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(open ? View.VISIBLE : View.GONE);

        head.setOnClickListener(v -> {
            boolean expand = body.getVisibility() != View.VISIBLE;
            body.setVisibility(expand ? View.VISIBLE : View.GONE);
            head.setText(header + " · " + count + " 条 " + (expand ? "▾" : "▸"));
            if (expand) expandedDays.add(dayKey); else expandedDays.remove(dayKey);
        });

        holder.addView(head);
        holder.addView(body);
        container.addView(holder);
        return body;
    }

    private TextView createHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(0xFF888888);
        tv.setPadding(dp(16), dp(16), dp(16), dp(6));
        return tv;
    }

    private View createSessionCard(LayoutInflater inflater, ReviewSession s, LinearLayout parent) {
        View card = inflater.inflate(R.layout.item_session, parent, false);

        TextView title = card.findViewById(R.id.tv_session_title);
        TextView stats = card.findViewById(R.id.tv_session_stats);
        TextView bank = card.findViewById(R.id.tv_session_bank);
        LinearLayout questions = card.findViewById(R.id.session_questions);

        int total = s.originalTotal();
        String statusText;
        if (ReviewSession.STATUS_FINISHED.equals(s.status)) {
            statusText = "已完成";
        } else if (ReviewSession.STATUS_ACTIVE.equals(s.status)) {
            statusText = "未完成 · 可继续";
        } else {
            statusText = "未完成";
        }

        String timeRange = SessionStorage.formatTime(s.startTime)
                + (s.endTime > 0 ? " - " + SessionStorage.formatTime(s.endTime) : "");
        String baseTitle = timeRange + " · " + total + " 题 · " + statusText;

        int know = s.countOfResult(ReviewSession.RESULT_KNOW);
        int vague = s.countOfResult(ReviewSession.RESULT_VAGUE);
        int forget = s.countOfResult(ReviewSession.RESULT_FORGET);
        int skip = s.skippedCount();
        StringBuilder sb = new StringBuilder();
        sb.append("记得 ").append(know)
                .append(" · 模糊 ").append(vague)
                .append(" · 不会 ").append(forget);
        if (skip > 0) sb.append(" · 跳过 ").append(skip);
        if (s.totalElapsedMs > 0) {
            sb.append(" · 用时 ").append(SessionStorage.formatDuration(s.totalElapsedMs));
            int answered = s.answeredCount();
            if (answered > 0) {
                sb.append(" · 平均 ").append(SessionStorage.formatDuration(s.totalElapsedMs / answered)).append("/题");
            }
        } else {
            sb.append(" · 用时 —");
        }
        stats.setText(sb.toString());

        boolean crossDay = s.endTime > 0 && !SessionStorage.isSameDay(s.startTime, s.endTime);
        bank.setText("题库：" + s.bankName + (crossDay ? " · 跨日复习" : ""));

        boolean open = expandedSessions.contains(s.id);
        if (open) {
            fillQuestionRows(questions, s);
            questions.setVisibility(View.VISIBLE);
            title.setText(baseTitle + " ▴");
        } else {
            questions.setVisibility(View.GONE);
            title.setText(baseTitle + " ▾");
        }
        card.setOnClickListener(v -> {
            boolean expand = questions.getVisibility() != View.VISIBLE;
            if (expand && questions.getChildCount() == 0) fillQuestionRows(questions, s);
            questions.setVisibility(expand ? View.VISIBLE : View.GONE);
            title.setText(baseTitle + (expand ? " ▴" : " ▾"));
            if (expand) expandedSessions.add(s.id); else expandedSessions.remove(s.id);
        });

        card.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("删除这条记录")
                    .setMessage("只删除复习足迹，不会删除题目。")
                    .setPositiveButton("删除", (d, w) -> {
                        SessionStorage.remove(this, s.id);
                        loadData();
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return true;
        });

        parent.addView(card);
        return card;
    }

    private void fillQuestionRows(LinearLayout parent, ReviewSession s) {
        String currentBankId = QuestionStorage.getCurrentBankId(this);
        boolean sameBank = s.bankId != null && s.bankId.equals(currentBankId);

        for (int i = 0; i < s.items.size(); i++) {
            ReviewSession.Item it = s.items.get(i);
            TextView row = new TextView(this);
            String label = labelOf(it);
            String text = (i + 1) + ". " + label + " " + it.contentSnapshot;
            if (it.hadImages) text += "（含图片）";
            if (it.dwellMs > 0) text += " · " + SessionStorage.formatDuration(it.dwellMs);
            row.setText(text);
            row.setTextSize(13);
            row.setTextColor(0xFF555555);
            row.setLineSpacing(dp(2), 1.1f);
            row.setPadding(0, dp(6), 0, dp(6));

            final String qid = it.questionId;
            row.setOnClickListener(v -> {
                if (!sameBank) {
                    Toast.makeText(this,
                            "该题属于其他题库「" + s.bankName + "」，请先切换题库再查看",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                Question q = QuestionStorage.findById(this, qid);
                if (q == null) {
                    Toast.makeText(this, "该题已被删除，仅保留记录", Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent intent = new Intent(this, QuestionDetailActivity.class);
                intent.putExtra("qid", qid);
                startActivity(intent);
            });

            parent.addView(row);
        }
    }

    private String labelOf(ReviewSession.Item it) {
        if (it.result == null) return "【未答】";
        switch (it.result) {
            case ReviewSession.RESULT_KNOW: return "【记得】";
            case ReviewSession.RESULT_VAGUE: return "【模糊】";
            case ReviewSession.RESULT_FORGET: return "【不会】";
            case ReviewSession.RESULT_SKIP: return "【跳过】";
            default: return "【未答】";
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
