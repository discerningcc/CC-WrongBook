package com.example.demo;

import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
 * 题目顺序在创建时固定，每题作答立即落盘，退出后可原位置续做；
 * 完成或被放弃后归档成「复习记录」。
 */
public class ReviewActivity extends AppCompatActivity {

    private TextView tvQuestion, tvAnswer, tvBanner, btnBannerNew;
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
    }

    private void bindActions() {
        findViewById(R.id.btn_know).setOnClickListener(v -> onKnow());
        findViewById(R.id.btn_unknown).setOnClickListener(v -> onUnknown());
        findViewById(R.id.btn_forget).setOnClickListener(v -> onForget());
        findViewById(R.id.btn_skip).setOnClickListener(v -> onSkip());
        findViewById(R.id.btn_back).setOnClickListener(v -> showAnswer());
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
        String tip = "已开始新一轮复习：共 " + session.items.size() + " 题";
        if (budget <= 0) {
            tip += "（不限量）";
        } else if (dueAll > session.items.size()) {
            tip += "（今日目标 " + budget + " · 已完成 " + done + " · 还剩 "
                    + (budget - done) + "，其余顺延）";
        }
        showBanner(tip);
        showCurrentQuestion();
    }

    /** 今日目标已答满后，用户选择「继续多做」：把全部到期题塞进来 */
    private void launchExtraSession() {
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
        if (showHint && session.answeredCount() > 0) {
            showBanner("已恢复上次进度：已答 " + session.answeredCount()
                    + " / " + session.items.size() + " 题", true);
        } else {
            hideBanner();
        }
        showCurrentQuestion();
    }

    private void showCrossDayPrompt(ReviewSession active) {
        int remaining = active.items.size() - active.currentIndex;
        int fresh = countFreshToday(active);

        StringBuilder msg = new StringBuilder();
        msg.append(SessionStorage.formatDateHeader(active.startTime)).append(" ")
                .append(SessionStorage.formatTime(active.startTime))
                .append(" · 题库「").append(active.bankName).append("」\n");
        msg.append("已答 ").append(active.answeredCount()).append(" 题，还剩 ")
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
        int pos = session.currentIndex + 1;
        int total = session.items.size();
        tvQuestion.setText("【等级：" + q.status + "】 【第 " + pos + " / " + total
                + " 题】 【ID：" + q.id + "】\n题目：\n" + q.content);
        tvAnswer.setText(q.answer);
        answerLayout.setVisibility(session.answerShown ? View.VISIBLE : View.GONE);

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
        findViewById(R.id.btn_back).setEnabled(enabled);
        findViewById(R.id.btn_skip).setEnabled(enabled);
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

    private void onKnow() {
        Question q = currentQuestion();
        if (q == null) return;
        switch (q.status == null ? "E" : q.status) {
            case "E": q.updateStatus("D"); break;
            case "D": q.updateStatus("C"); break;
            case "C": q.updateStatus("B"); break;
            case "B": q.updateStatus("A"); break;
            case "A": q.updateStatus("S"); break;
            case "S": q.updateStatus("S"); break;   // 长期题记得：保持 S，重算 30 天
            default: break;
        }
        QuestionStorage.update(this);
        recordResult(ReviewSession.RESULT_KNOW);
    }

    private void onUnknown() {
        Question q = currentQuestion();
        if (q == null) return;
        // 模糊：等级不动，但间隔按低一级算（把更新时间往回拨 own-low 天，到期自然提前）
        String lower = QuestionStorage.lowerLevelOf(q.status);
        int own = QuestionStorage.intervalDaysOf(q.status);
        int low = QuestionStorage.intervalDaysOf(lower);
        if (own > low) {
            q.updateTime = System.currentTimeMillis() - (long) (own - low) * 24L * 3600 * 1000;
        } else {
            q.updateStatus(q.status);
        }
        QuestionStorage.update(this);
        recordResult(ReviewSession.RESULT_VAGUE);
    }

    private void onForget() {
        Question q = currentQuestion();
        if (q == null) return;
        if ("S".equals(q.status)) {
            q.updateStatus("A");   // 长期题答错：降回 A（15 天），不直接打回 E
        } else {
            q.updateStatus("E");
        }
        QuestionStorage.update(this);
        recordResult(ReviewSession.RESULT_FORGET);
    }

    /** 跳过：不动等级、不刷新 updateTime，只把这道题记成跳过 */
    private void onSkip() {
        if (currentQuestion() == null) return;
        recordResult(ReviewSession.RESULT_SKIP);
    }

    private void showAnswer() {
        if (session == null) return;
        answerLayout.setVisibility(View.VISIBLE);
        session.answerShown = true;
        SessionStorage.save(this, session);
    }

    private void recordResult(String result) {
        if (session == null || session.currentIndex >= session.items.size()) return;
        ReviewSession.Item item = session.items.get(session.currentIndex);
        item.result = result;
        item.answeredAt = System.currentTimeMillis();

        // 答错（不会）：本题插到本次队列末尾趁热再看一次，最多重做 1 次
        if (ReviewSession.RESULT_FORGET.equals(result)) {
            int copies = 0;
            for (ReviewSession.Item it : session.items) {
                if (item.questionId.equals(it.questionId)) copies++;
            }
            if (copies < 2) {
                ReviewSession.Item again = new ReviewSession.Item();
                again.questionId = item.questionId;
                again.contentSnapshot = item.contentSnapshot;
                again.hadImages = item.hadImages;
                session.items.add(again);
            }
        }

        // 今日目标计数：记得/模糊/不会算 1 道；跳过不算；
        // 答错重做的第二遍不重复扣（同一题已答过一次）
        if (!ReviewSession.RESULT_SKIP.equals(result)) {
            boolean isRedo = false;
            for (ReviewSession.Item it : session.items) {
                if (it != item && item.questionId.equals(it.questionId) && it.answeredAt > 0) {
                    isRedo = true;
                    break;
                }
            }
            if (!isRedo) QuestionStorage.incTodayDone(this);
        }

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

    // ==================== 收尾 ====================

    private void finishSession() {
        if (finished) return;
        finished = true;

        int total = 0, know = 0, vague = 0, forget = 0, skip = 0;
        if (session != null) {
            session.status = ReviewSession.STATUS_FINISHED;
            session.endTime = System.currentTimeMillis();
            session.currentIndex = session.items.size();
            session.answerShown = false;
            SessionStorage.save(this, session);

            total = session.items.size();
            know = session.countOfResult(ReviewSession.RESULT_KNOW);
            vague = session.countOfResult(ReviewSession.RESULT_VAGUE);
            forget = session.countOfResult(ReviewSession.RESULT_FORGET);
            skip = session.skippedCount();
        }

        String stats = "共 " + total + " 题\n记得 " + know + " · 模糊 " + vague + " · 不会 " + forget
                + (skip > 0 ? " · 跳过 " + skip : "");

        tvQuestion.setText("本次复习已完成\n\n" + stats);
        tvAnswer.setText("");
        answerLayout.setVisibility(View.GONE);
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
        int answered = session.answeredCount();
        if (answered == 0) {
            // 一题都没答，不留空记录
            SessionStorage.remove(this, session.id);
            finish();
            return;
        }
        showPrompt("退出复习？",
                "已答 " + answered + " / " + session.items.size() + " 题，进度已保存\n下次进入可继续",
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
