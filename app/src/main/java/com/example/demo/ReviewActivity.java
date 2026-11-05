package com.example.demo;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.github.chrisbanes.photoview.PhotoView;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 复习页：一次复习 = 一个 ReviewSession。
 * 题目顺序在创建时固定；作答先入延迟提交缓冲，下一道作答 / 收尾 / 退出 / 切后台时落盘，
 * 未提交前可经菜单「上一题」退回重答（最多一步）；退出后可原位置续做，完成或放弃后归档。
 */
public class ReviewActivity extends AppCompatActivity {

    private TextView tvQuestion, tvAnswer, tvBanner, btnBannerNew, btnAnswerText;
    private LinearLayout reviewImageLayout, answerImageLayout, answerLayout;
    private LinearLayout bannerContainer;

    private ReviewSession session;
    private String bankId;
    private String bankName;
    private boolean finished;

    /** 顶部提示条自动消失的秒数 */
    private static final int BANNER_SECONDS = 8;
    private CountDownTimer bannerTimer;
    private String bannerBaseText = "";

    /**
     * 待提交的上一道作答（延迟提交缓冲）：作答先只入内存，
     * 在下一道作答 / 收尾 / 退出 / 切后台时才真正落盘；
     * 未提交前可经菜单「上一题」丢弃缓冲退回重答，最多退一步。
     */
    private PendingAnswer pending;

    private static class PendingAnswer {
        int index;                  // 对应 items 下标
        String result;             // KNOW / VAGUE / FORGET / SKIP / EXCLUDED
        String afterStatus;         // 提交时写入的等级；null 表示不动等级
        long afterUpdateTime;       // 提交时写入的更新时间
        boolean excludeApplied;      // 提交时置为已斩
        boolean redoCopy;           // 不会题：提交时追加队尾重做副本
        boolean counted;            // 提交时是否计入今日完成
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_review);

        bankId = QuestionStorage.getCurrentBankId(this);
        bankName = QuestionStorage.getCurrentBankName(this);

        bindViews();
        bindActions();
        bindBackPressed();

        ReviewSession active = SessionStorage.getActive(this, bankId);
        if (active == null) {
            startNewSession(false);
        } else if (!SessionStorage.isSameDay(active.startTime, System.currentTimeMillis())) {
            // 跨自然日：交给用户决定继续还是重新开始
            showCrossDayPrompt(active);
        } else {
            // savedInstanceState 不为空 = 这次 onCreate 是系统重建（横竖屏切换、分屏切换等），
            // 用户并没有"重新进来"，不该再弹一次「已恢复上次进度」的提示条
            resumeSession(active, savedInstanceState == null);
        }
    }

    private void bindViews() {
        tvQuestion = findViewById(R.id.tv_question);
        tvAnswer = findViewById(R.id.tv_answer);
        reviewImageLayout = findViewById(R.id.review_image_layout);
        answerImageLayout = findViewById(R.id.answer_image_layout);
        answerLayout = findViewById(R.id.answer_layout);
        bannerContainer = findViewById(R.id.session_banner);
        tvBanner = findViewById(R.id.tv_banner);
        btnBannerNew = findViewById(R.id.btn_banner_new);
        btnAnswerText = findViewById(R.id.btn_answer_text);
    }

    private void bindActions() {
        findViewById(R.id.btn_know).setOnClickListener(v -> onKnow());
        findViewById(R.id.btn_unknown).setOnClickListener(v -> onUnknown());
        findViewById(R.id.btn_forget).setOnClickListener(v -> onForget());
        findViewById(R.id.btn_menu).setOnClickListener(v -> openReviewMenu(v));
        findViewById(R.id.btn_answer).setOnClickListener(v -> toggleAnswer());
        findViewById(R.id.btn_banner_close).setOnClickListener(v -> hideBanner());
        findViewById(R.id.btn_banner_new).setOnClickListener(v -> confirmRestart());
    }

    private void bindBackPressed() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                confirmExit();
            }
        });
    }

    // ==================== 会话的三种入口 ====================

    private void startNewSession(boolean archivePrevious) {
        commitPending();
        if (archivePrevious) {
            ReviewSession old = SessionStorage.getActive(this, bankId);
            if (old != null) SessionStorage.archive(this, old, ReviewSession.STATUS_ABANDONED);
        }
        List<Question> today = QuestionStorage.buildTodayQueue(this);
        int budget = QuestionStorage.getDailyBudget(this);
        int dueAll = QuestionStorage.getTodayReview(this).size();
        if (today == null || today.isEmpty()) {
            // 队列空：可能是真没题到期，也可能是今日目标已完成（还有题但预算额度没了）
            if (budget > 0 && dueAll > 0) {
                int done = QuestionStorage.getTodayDone(this);
                showPrompt("今日目标已完成",
                        "今日目标 " + budget + " 道，已完成 " + done + " 道；\n还有 " + dueAll + " 道到期，要继续多做一些吗？",
                        "继续多做（" + dueAll + " 道）", this::launchExtraSession,
                        "回首页", this::finish,
                        null, null,
                        null);
            } else {
                showEmptyState();
            }
            return;
        }
        Collections.shuffle(today);
        session = SessionStorage.create(this, bankId, bankName, today);
        finished = false;
        int done = QuestionStorage.getTodayDone(this);
        String tip = "已开始新一轮复习：共 " + session.originalTotal() + " 题";
        if (budget <= 0) {
            tip += "（不限量）";
        } else if (dueAll > session.originalTotal()) {
            tip += "（今日目标 " + budget + " · 已完成 " + done + " · 还剩 "
                    + (budget - done) + "，其余顺延）";
        }
        showBanner(tip);
        showCurrentQuestion();
    }

    /** 今日目标已答满后，用户选择「继续多做」：把全部到期题塞进来 */
    private void launchExtraSession() {
        commitPending();
        List<Question> all = QuestionStorage.getTodayReview(this);
        Collections.shuffle(all);
        session = SessionStorage.create(this, bankId, bankName, all);
        finished = false;
        showBanner("今日目标已超额，复习全部剩余到期题：共 " + all.size() + " 题");
        showCurrentQuestion();
    }

    private void resumeSession(ReviewSession active, boolean showHint) {
        session = active;
        finished = false;
        if (session.currentIndex >= session.items.size()) {
            // 上次其实已经答完但没归档，直接收尾
            finishSession();
            return;
        }
        if (showHint && session.closedOriginalCount() > 0) {
            showBanner("已恢复上次进度：已完成 " + session.closedOriginalCount()
                    + " / " + session.originalTotal() + " 题", true);
        } else {
            hideBanner();
        }
        showCurrentQuestion();
    }

    private void showCrossDayPrompt(ReviewSession active) {
        int remaining = Math.max(0, active.originalTotal() - active.closedOriginalCount());
        int fresh = countFreshToday(active);

        StringBuilder msg = new StringBuilder();
        msg.append(SessionStorage.formatDateHeader(active.startTime)).append(" ")
                .append(SessionStorage.formatTime(active.startTime))
                .append(" · 题库「").append(active.bankName).append("」\n");
        msg.append("已完成 ").append(active.closedOriginalCount()).append(" 题，还剩 ")
                .append(remaining).append(" 题\n");
        msg.append("本次会话已跨日，将按 ")
                .append(SessionStorage.formatDateHeader(active.startTime))
                .append(" 归入复习记录");
        if (fresh > 0) {
            msg.append("\n\n今日另有 ").append(fresh).append(" 题新到期，不在本次会话内");
        }

        showPrompt("上次复习未完成", msg.toString(),
                "继续上次（剩 " + remaining + " 题）", () -> resumeSession(active, false),
                "开始今天的新一轮", () -> startNewSession(true),
                "取消（退出复习）", this::finish,
                this::finish);
    }

    /** 今日到期但完全不在这条会话里的题数 */
    private int countFreshToday(ReviewSession active) {
        Set<String> inSession = new HashSet<>();
        for (ReviewSession.Item it : active.items) inSession.add(it.questionId);
        int fresh = 0;
        for (Question q : QuestionStorage.getTodayReview(this)) {
            if (!inSession.contains(q.id)) fresh++;
        }
        return fresh;
    }

    // ==================== 显示 ====================

    private void showCurrentQuestion() {
        if (session == null) return;
        while (session.currentIndex < session.items.size()) {
            ReviewSession.Item item = session.items.get(session.currentIndex);
            Question q = QuestionStorage.findById(this, item.questionId);
            if (q != null) {
                renderQuestion(q);
                return;
            }
            // 原题已被删除：标记跳过并前进，不让复习卡住
            item.result = ReviewSession.RESULT_SKIP;
            item.answeredAt = System.currentTimeMillis();
            session.currentIndex++;
            SessionStorage.save(this, session);
        }
        finishSession();
    }

    private void renderQuestion(Question q) {
        int done = displayClosedCount();
        int total = session.originalTotal();
        tvQuestion.setText("【等级：" + q.status + "】 【已完成 " + done + " / " + total
                + " 题】 【ID：" + q.id + "】\n题目：\n" + q.content);
        tvAnswer.setText(q.answer);
        answerLayout.setVisibility(session.answerShown ? View.VISIBLE : View.GONE);
        syncAnswerButton();

        reviewImageLayout.removeAllViews();
        if (q.contentImages != null) {
            for (String path : q.contentImages) addImage(reviewImageLayout, path);
        }

        answerImageLayout.removeAllViews();
        if (q.answerImages != null) {
            for (String path : q.answerImages) addImage(answerImageLayout, path);
        }

        enableButtons(true);
    }

    private void showEmptyState() {
        tvQuestion.setText("🎉 今天没有需要复习的题目！");
        tvAnswer.setText("");
        answerLayout.setVisibility(View.GONE);
        syncAnswerButton();
        reviewImageLayout.removeAllViews();
        answerImageLayout.removeAllViews();
        hideBanner();
        enableButtons(false);
    }

    /** 显示顶部提示条，带倒计时，到点自动消失 */
    private void showBanner(String text) {
        showBanner(text, false);
    }

    /**
     * @param showRestart 是否在提示条里给出「开始新一轮」（只在"恢复上次进度"时出现，
     *                    新开一轮的时候没必要再放这个按钮）
     */
    private void showBanner(String text, boolean showRestart) {
        if (bannerContainer == null) return;
        bannerBaseText = text;
        bannerContainer.setVisibility(View.VISIBLE);
        if (btnBannerNew != null) {
            btnBannerNew.setVisibility(showRestart ? View.VISIBLE : View.GONE);
        }

        if (bannerTimer != null) bannerTimer.cancel();
        bannerTimer = new CountDownTimer(BANNER_SECONDS * 1000L, 1000L) {
            @Override
            public void onTick(long millisUntilFinished) {
                int sec = (int) Math.ceil(millisUntilFinished / 1000.0);
                tvBanner.setText(bannerBaseText + "（" + sec + " 秒后自动消失）");
            }

            @Override
            public void onFinish() {
                hideBanner();
            }
        };
        tvBanner.setText(bannerBaseText + "（" + BANNER_SECONDS + " 秒后自动消失）");
        bannerTimer.start();
    }

    private void hideBanner() {
        if (bannerTimer != null) {
            bannerTimer.cancel();
            bannerTimer = null;
        }
        if (btnBannerNew != null) btnBannerNew.setVisibility(View.GONE);
        if (bannerContainer != null) bannerContainer.setVisibility(View.GONE);
    }

    /** 「开始新一轮」：当前这一轮先存档，再按今天到期的题重新抽一批 */
    private void confirmRestart() {
        if (session == null || finished) return;
        showPrompt("开始新一轮复习？",
                "当前这一轮会存档到「复习记录」，然后按今天到期的题重新抽一批。",
                "开始新一轮", () -> startNewSession(true),
                "取消", () -> { },
                null, null,
                null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        commitPending();               // 切后台先把未提交的作答落盘，避免进程回收丢答案
        QuestionStorage.flush(this);   // 离开页面时把还没落盘的题目状态写完
    }

    @Override
    protected void onDestroy() {
        if (bannerTimer != null) bannerTimer.cancel();
        bannerTimer = null;
        super.onDestroy();
    }

    private void enableButtons(boolean enabled) {
        findViewById(R.id.btn_know).setEnabled(enabled);
        findViewById(R.id.btn_unknown).setEnabled(enabled);
        findViewById(R.id.btn_forget).setEnabled(enabled);
        findViewById(R.id.btn_answer).setEnabled(enabled);
        findViewById(R.id.btn_menu).setEnabled(enabled);
    }

    private void addImage(LinearLayout parent, String path) {
        ImageView iv = new ImageView(this);
        int size = (int) (280 * getResources().getDisplayMetrics().density);
        iv.setLayoutParams(new LinearLayout.LayoutParams(size, LinearLayout.LayoutParams.WRAP_CONTENT));
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setAdjustViewBounds(true);
        iv.setImageURI(android.net.Uri.parse("file://" + path));
        iv.setOnClickListener(v -> showFullImage(path));
        parent.addView(iv);
    }

    private void showFullImage(String path) {
        Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        View view = getLayoutInflater().inflate(R.layout.dialog_photo_view, null);
        PhotoView photoView = view.findViewById(R.id.photo_view);
        photoView.setImageURI(android.net.Uri.parse("file://" + path));

        Window window = dialog.getWindow();
        if (window != null) {
            window.getDecorView().setPadding(0, 0, 0, 0);
            window.setBackgroundDrawableResource(android.R.color.black);
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            params.height = WindowManager.LayoutParams.MATCH_PARENT;
            window.setAttributes(params);
        }

        dialog.setContentView(view);
        dialog.show();

        photoView.setOnPhotoTapListener((v, x, y) -> dialog.dismiss());
    }

    // ==================== 作答 ====================

    private Question currentQuestion() {
        if (session == null || session.currentIndex >= session.items.size()) return null;
        return QuestionStorage.findById(this, session.items.get(session.currentIndex).questionId);
    }

    private void onKnow() { answerQuestion(ReviewSession.RESULT_KNOW); }

    private void onUnknown() { answerQuestion(ReviewSession.RESULT_VAGUE); }

    private void onForget() { answerQuestion(ReviewSession.RESULT_FORGET); }

    /** 记得后的升档表：E→D→C→B→A→S，S 保持 S 并重算 30 天 */
    private String upgradeLevel(String status) {
        switch (status == null ? "E" : status) {
            case "E": return "D";
            case "D": return "C";
            case "C": return "B";
            case "B": return "A";
            case "A": return "S";
            case "S": return "S";
            default: return "E";
        }
    }

    /**
     * 统一作答入口：先提交上一道缓冲，再把当前这道的结果与题目改动暂存进 pending，
     * 界面前进；题目等级/时间/斩题标记/重做副本/今日计数都延迟到提交时落地。
     */
    private void answerQuestion(String result) {
        if (session == null || session.currentIndex >= session.items.size()) return;
        commitPending();

        Question q = currentQuestion();
        if (q == null) return;
        ReviewSession.Item item = session.items.get(session.currentIndex);
        long now = System.currentTimeMillis();

        PendingAnswer p = new PendingAnswer();
        p.index = session.currentIndex;
        p.result = result;
        p.afterStatus = null;
        p.afterUpdateTime = q.updateTime;
        p.excludeApplied = false;
        p.redoCopy = false;

        switch (result) {
            case ReviewSession.RESULT_KNOW:
                p.afterStatus = upgradeLevel(q.status);
                p.afterUpdateTime = now;
                break;
            case ReviewSession.RESULT_VAGUE: {
                // 模糊：等级不动，间隔按低一级算（时间往回拨 own-low 天，到期自然提前）
                String lower = QuestionStorage.lowerLevelOf(q.status);
                int own = QuestionStorage.intervalDaysOf(q.status);
                int low = QuestionStorage.intervalDaysOf(lower);
                p.afterStatus = q.status;
                p.afterUpdateTime = own > low
                        ? now - (long) (own - low) * 24L * 3600 * 1000
                        : now;
                break;
            }
            case ReviewSession.RESULT_FORGET:
                p.afterStatus = "S".equals(q.status) ? "A" : "E";  // 长期题答错降回 A，其余打回 E
                p.afterUpdateTime = now;
                p.redoCopy = true;
                break;
            case ReviewSession.RESULT_EXCLUDED:
                p.excludeApplied = true;
                break;
            case ReviewSession.RESULT_SKIP:
                break;
        }
        p.counted = shouldCount(result, item);

        pending = p;
        session.currentIndex++;
        session.answerShown = false;
        hideBanner();
        SessionStorage.save(this, session);

        if (session.currentIndex >= session.items.size()) {
            finishSession();
        } else {
            showCurrentQuestion();
        }
    }

    /**
     * 今日完成计数口径（在已提交历史上判断是否重做）：
     * 记得首遍/重做都 +1；模糊仅首遍 +1；不会永不计；跳过、斩题不计。
     */
    private boolean shouldCount(String result, ReviewSession.Item self) {
        if (ReviewSession.RESULT_SKIP.equals(result)
                || ReviewSession.RESULT_EXCLUDED.equals(result)) return false;
        if (ReviewSession.RESULT_KNOW.equals(result)) return true;
        if (ReviewSession.RESULT_VAGUE.equals(result)) {
            for (ReviewSession.Item it : session.items) {
                if (it != self && self.questionId.equals(it.questionId) && it.answeredAt > 0) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /** 提交当前缓冲：把暂存的题目改动、副本、计数、会话结果真正落盘 */
    private void commitPending() {
        if (pending == null || session == null) return;
        ReviewSession.Item item = session.items.get(pending.index);
        Question q = QuestionStorage.findById(this, item.questionId);
        if (q != null) {
            if (pending.afterStatus != null) q.status = pending.afterStatus;
            if (pending.afterUpdateTime != q.updateTime) q.updateTime = pending.afterUpdateTime;
            if (pending.excludeApplied) q.excluded = true;
            QuestionStorage.update(this);
        }

        // 不会题：提交时把重做副本插到队尾，最多 REDO_CAP 次
        if (pending.redoCopy) {
            int copies = 0;
            for (ReviewSession.Item it : session.items) {
                if (item.questionId.equals(it.questionId)) copies++;
            }
            if (copies <= ReviewSession.REDO_CAP) {
                ReviewSession.Item again = new ReviewSession.Item();
                again.questionId = item.questionId;
                again.contentSnapshot = item.contentSnapshot;
                again.hadImages = item.hadImages;
                again.redo = true;
                session.items.add(again);
            }
        }

        if (pending.counted) QuestionStorage.incTodayDone(this);
        item.result = pending.result;
        item.answeredAt = System.currentTimeMillis();
        pending = null;
        SessionStorage.save(this, session);
    }

    /** 上一题：丢弃未提交缓冲，退回那道题重答；最多一步，已提交的不能再退 */
    private void onPrevious() {
        if (session == null || finished || pending == null) return;
        session.currentIndex = pending.index;
        pending = null;
        session.answerShown = false;
        SessionStorage.save(this, session);
        showCurrentQuestion();
    }

    /**
     * 页面展示用已完成数：在已提交口径上，再把未提交缓冲里「完成类」作答临时计入；
     * 仅影响页头显示，不落盘——缓冲被退回丢弃后计数自然回退。
     */
    private int displayClosedCount() {
        int n = session.closedOriginalCount();
        if (pending == null || session == null) return n;
        ReviewSession.Item self = session.items.get(pending.index);
        String id = self.questionId;
        if (id == null) return n;
        boolean closedPending = ReviewSession.RESULT_KNOW.equals(pending.result)
                || ReviewSession.RESULT_VAGUE.equals(pending.result)
                || ReviewSession.RESULT_SKIP.equals(pending.result)
                || ReviewSession.RESULT_EXCLUDED.equals(pending.result);
        if (!closedPending) return n;
        // 同一题在已提交部分已算过完成，避免重复 +1
        for (ReviewSession.Item it : session.items) {
            if (id.equals(it.questionId) && it.answeredAt > 0
                    && (ReviewSession.RESULT_KNOW.equals(it.result)
                    || ReviewSession.RESULT_VAGUE.equals(it.result)
                    || ReviewSession.RESULT_SKIP.equals(it.result)
                    || ReviewSession.RESULT_EXCLUDED.equals(it.result))) {
                return n;
            }
        }
        return n + 1;
    }

    /** 右上角溢出菜单：上一题 / 跳过 / 斩题等低频操作集中在此 */
    private void openReviewMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenuInflater().inflate(R.menu.review_menu, popup.getMenu());
        // 无待提交缓冲时「上一题」不可用
        popup.getMenu().findItem(R.id.menu_previous)
                .setEnabled(pending != null && !finished);
        popup.setOnMenuItemClickListener((MenuItem item) -> {
            int id = item.getItemId();
            if (id == R.id.menu_previous) {
                onPrevious();
                return true;
            }
            if (id == R.id.menu_exclude) {
                onExclude();
                return true;
            }
            if (id == R.id.menu_skip) {
                onSkip();
                return true;
            }
            return false;
        });
        popup.show();
    }

    /** 斩题：软标记退出复习，不计完成数、不回炉；题目本体保留可恢复 */
    private void onExclude() {
        Question q = currentQuestion();
        if (q == null) return;
        new AlertDialog.Builder(this)
                .setTitle("斩题")
                .setMessage("这道题将退出复习队列和长期抽查，不再占用今日任务；题目本体保留，可在题目列表恢复。\n确定斩题吗？")
                .setPositiveButton("斩题", (dialog, which) -> answerQuestion(ReviewSession.RESULT_EXCLUDED))
                .setNegativeButton("取消", null)
                .show();
    }

    /** 跳过：不动等级、不刷新 updateTime，只把这道题记成跳过 */
    private void onSkip() {
        if (currentQuestion() == null) return;
        answerQuestion(ReviewSession.RESULT_SKIP);
    }

    private void toggleAnswer() {
        if (session == null) return;
        boolean shown = answerLayout.getVisibility() == View.VISIBLE;
        if (shown) {
            answerLayout.setVisibility(View.GONE);
            session.answerShown = false;
        } else {
            answerLayout.setVisibility(View.VISIBLE);
            session.answerShown = true;
        }
        SessionStorage.save(this, session);
        syncAnswerButton();
    }

    private void syncAnswerButton() {
        if (btnAnswerText != null) {
            btnAnswerText.setText(answerLayout.getVisibility() == View.VISIBLE ? "收起" : "答案");
        }
    }

    // ==================== 收尾 ====================

    private void finishSession() {
        if (finished) return;
        commitPending();
        finished = true;

        int total = 0, know = 0, vague = 0, forget = 0, skip = 0, excluded = 0;
        if (session != null) {
            session.status = ReviewSession.STATUS_FINISHED;
            session.endTime = System.currentTimeMillis();
            session.currentIndex = session.items.size();
            session.answerShown = false;
            SessionStorage.save(this, session);

            total = session.originalTotal();
            int[] u = session.uniqueStats();
            know = u[0];
            vague = u[1];
            forget = u[2];
            skip = u[3];
            excluded = u[4];
        }

        String stats = "共 " + total + " 题\n记得 " + know + " · 模糊 " + vague + " · 不会 " + forget
                + (skip > 0 ? " · 跳过 " + skip : "")
                + (excluded > 0 ? " · 斩题 " + excluded : "");

        tvQuestion.setText("本次复习已完成\n\n" + stats);
        tvAnswer.setText("");
        answerLayout.setVisibility(View.GONE);
        syncAnswerButton();
        reviewImageLayout.removeAllViews();
        answerImageLayout.removeAllViews();
        hideBanner();
        enableButtons(false);

        if (skip > 0) {
            showPrompt("复习完成", stats,
                    "重做跳过的 " + skip + " 题", this::redoSkpped,
                    "查看本次记录", this::openHistory,
                    "返回首页", this::finish,
                    null);
        } else {
            showPrompt("复习完成", stats,
                    "查看本次记录", this::openHistory,
                    "返回首页", this::finish,
                    null, null,
                    null);
        }
    }

    /** 把本次跳过的题目重新拉回队列，复用同一条会话 */
    private void redoSkpped() {
        if (session == null) return;
        int firstIdx = -1;
        for (int i = 0; i < session.items.size(); i++) {
            ReviewSession.Item it = session.items.get(i);
            if (ReviewSession.RESULT_SKIP.equals(it.result)) {
                it.result = null;
                it.answeredAt = 0;
                if (firstIdx < 0) firstIdx = i;
            }
        }
        if (firstIdx < 0) return;

        session.currentIndex = firstIdx;
        session.status = ReviewSession.STATUS_ACTIVE;
        session.endTime = 0;
        session.answerShown = false;
        SessionStorage.save(this, session);
        finished = false;
        showCurrentQuestion();
    }

    private void confirmExit() {
        if (finished || session == null) {
            finish();
            return;
        }
        commitPending();
        int answered = session.answeredCount();
        if (answered == 0) {
            // 一题都没答，不留空记录
            SessionStorage.remove(this, session.id);
            finish();
            return;
        }
        showPrompt("退出复习？",
                "已完成 " + session.closedOriginalCount() + " / " + session.originalTotal() + " 题，进度已保存\n下次进入可继续",
                "继续复习", () -> {},
                "退出", this::finish,
                null, null,
                null);
    }

    private void openHistory() {
        startActivity(new Intent(this, ReviewHistoryActivity.class));
        finish();
    }

    // ==================== 通用弹窗 ====================

    /**
     * 三个竖排按钮的弹窗，任一按钮文案为 null 时隐藏。
     * onCancel 为点击外部/返回键时的行为，可为 null。
     */
    private void showPrompt(String title, String message,
                            String b1Text, Runnable b1,
                            String b2Text, Runnable b2,
                            String b3Text, Runnable b3,
                            Runnable onCancel) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        View view = getLayoutInflater().inflate(R.layout.dialog_common_prompt, null);
        ((TextView) view.findViewById(R.id.tv_dialog_title)).setText(title);
        ((TextView) view.findViewById(R.id.tv_dialog_message)).setText(message);

        setupPromptButton(view, dialog, R.id.btn_dialog_primary, b1Text, b1);
        setupPromptButton(view, dialog, R.id.btn_dialog_secondary, b2Text, b2);
        setupPromptButton(view, dialog, R.id.btn_dialog_tertiary, b3Text, b3);

        if (onCancel != null) {
            dialog.setOnCancelListener(d -> onCancel.run());
        }

        dialog.setContentView(view);
        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.9f);
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private void setupPromptButton(View root, Dialog dialog, int id, String text, Runnable action) {
        TextView btn = root.findViewById(id);
        if (text == null || action == null) {
            btn.setVisibility(View.GONE);
            return;
        }
        btn.setText(text);
        btn.setOnClickListener(v -> {
            dialog.dismiss();
            action.run();
        });
    }
}
