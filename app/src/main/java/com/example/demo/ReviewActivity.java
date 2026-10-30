package com.example.demo;

import android.app.Dialog;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.github.chrisbanes.photoview.PhotoView;
import java.util.Collections;
import java.util.List;

public class ReviewActivity extends AppCompatActivity {

    private TextView tvQuestion, tvAnswer;
    private LinearLayout reviewImageLayout, answerImageLayout, answerLayout;
    private List<Question> reviewList;
    private int currentIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_review);

        tvQuestion = findViewById(R.id.tv_question);
        tvAnswer = findViewById(R.id.tv_answer);
        reviewImageLayout = findViewById(R.id.review_image_layout);
        answerLayout = findViewById(R.id.answer_layout);
        answerImageLayout = findViewById(R.id.answer_image_layout);

        reviewList = QuestionStorage.getTodayReview(this);

        // 随机打乱题目
        if (reviewList != null) {
            Collections.shuffle(reviewList);
        }

        if (reviewList == null || reviewList.isEmpty()) {
            tvQuestion.setText("🎉 今天没有需要复习的题目！");
            disableBtn();
            return;
        }

        // 初始只显示题目，答案隐藏
        showCurrentQuestion();

        // 1. 记得
        findViewById(R.id.btn_know).setOnClickListener(v -> onKnow());
        // 2. 模糊
        findViewById(R.id.btn_unknown).setOnClickListener(v -> onUnknown());
        // 3. 不会
        findViewById(R.id.btn_forget).setOnClickListener(v -> onForget());
        // 4. 原返回按钮 → 现在是：显示答案
        findViewById(R.id.btn_back).setOnClickListener(v -> {
            answerLayout.setVisibility(View.VISIBLE);
        });
    }

    private void showCurrentQuestion() {
        Question q = reviewList.get(currentIndex);

// 等级 + 题目ID，用户一目了然
        tvQuestion.setText("【等级：" + q.status + "】 【ID：" + q.id + "】\n题目：\n" + q.content);
        // 初始隐藏答案
        answerLayout.setVisibility(View.GONE);
        tvAnswer.setText(q.answer);

        // 题目图片
        reviewImageLayout.removeAllViews();
        for (String path : q.contentImages) {
            addImage(reviewImageLayout, path, true);
        }

        // 答案图片
        answerImageLayout.removeAllViews();
        for (String path : q.answerImages) {
            addImage(answerImageLayout, path, true);
        }
    }

    private void addImage(LinearLayout parent, String path, boolean clickable) {
        ImageView iv = new ImageView(this);
        int size = (int) (280 * getResources().getDisplayMetrics().density);
        iv.setLayoutParams(new LinearLayout.LayoutParams(size, LinearLayout.LayoutParams.WRAP_CONTENT));
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setAdjustViewBounds(true);
        iv.setImageURI(android.net.Uri.parse("file://" + path));

        if (clickable) {
            iv.setOnClickListener(v -> showFullImage(path));
        }

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

    private void onKnow() {
        Question q = reviewList.get(currentIndex);
        switch (q.status) {
            case "E": q.updateStatus("D"); break;
            case "D": q.updateStatus("C"); break;
            case "C": q.updateStatus("B"); break;
            case "B": q.updateStatus("A"); break;
        }
        next();
    }

    private void onUnknown() {
        reviewList.get(currentIndex).updateStatus(reviewList.get(currentIndex).status);
        next();
    }

    private void onForget() {
        reviewList.get(currentIndex).updateStatus("E");
        next();
    }

    private void next() {
        QuestionStorage.update(this);
        currentIndex++;
        if (currentIndex >= reviewList.size()) {
            tvQuestion.setText("✅ 复习完成！");
            reviewImageLayout.removeAllViews();
            answerLayout.setVisibility(View.GONE);
            disableBtn();
        } else {
            showCurrentQuestion();
        }
    }

    private void disableBtn() {
        findViewById(R.id.btn_know).setEnabled(false);
        findViewById(R.id.btn_unknown).setEnabled(false);
        findViewById(R.id.btn_forget).setEnabled(false);
        findViewById(R.id.btn_back).setEnabled(false);
    }
}