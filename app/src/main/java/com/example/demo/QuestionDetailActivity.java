package com.example.demo;
import android.widget.Toast;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.List;

public class QuestionDetailActivity extends AppCompatActivity {

    private TextView tvQuestion, tvAnswer;
    private LinearLayout reviewImageLayout, answerImageLayout, answerLayout;
    private Question currentQuestion;
    private final List<String> fullscreenPaths = new ArrayList<>();
    private String currentQid; // 保存ID，用于刷新
    private com.google.android.material.card.MaterialCardView btnExclude;
    private com.google.android.material.card.MaterialCardView btnRestudy;
    private TextView btnExcludeText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_question_detail);

        tvQuestion = findViewById(R.id.tv_question);
        tvAnswer = findViewById(R.id.tv_answer);
        reviewImageLayout = findViewById(R.id.review_image_layout);
        answerLayout = findViewById(R.id.answer_layout);
        answerImageLayout = findViewById(R.id.answer_image_layout);
        btnExclude = findViewById(R.id.btn_exclude);
        btnExcludeText = findViewById(R.id.btn_exclude_text);
        btnRestudy = findViewById(R.id.btn_restudy);

        // 拿到ID并保存
        currentQid = getIntent().getStringExtra("qid");
        loadQuestionData(); // 加载题目


        // 修改本题
        findViewById(R.id.btn_edit).setOnClickListener(v -> {
            Intent intent = new Intent(QuestionDetailActivity.this, AddQuestionActivity.class);
            intent.putExtra("edit_qid", currentQid);
            startActivity(intent);
        });

        // 重学本题（新加）
        findViewById(R.id.btn_restudy).setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("重学本题")
                    .setMessage("确定要将此题重置为「未掌握」吗？\n将重新进入复习计划。")
                    .setPositiveButton("确定", (dialog, which) -> {
                        currentQuestion.status = "E";
                        currentQuestion.updateTime = System.currentTimeMillis();
                        QuestionStorage.update(this);
                        Toast.makeText(this, "已重置，将重新复习", Toast.LENGTH_SHORT).show();
                        loadQuestionData(); // 刷新页面等级显示
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });

        // 斩题 / 恢复本题
        btnExclude.setOnClickListener(v -> {
            if (currentQuestion == null) return;
            if (currentQuestion.excluded) {
                new AlertDialog.Builder(this)
                        .setTitle("恢复此题")
                        .setMessage("确定恢复这道题吗？\n将重置为 E 级，重新进入复习计划。")
                        .setPositiveButton("恢复", (dialog, which) -> {
                            QuestionStorage.restoreQuestion(this, currentQuestion);
                            Toast.makeText(this, "已恢复，重新开始复习", Toast.LENGTH_SHORT).show();
                            loadQuestionData();
                        })
                        .setNegativeButton("取消", null)
                        .show();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("斩题")
                        .setMessage("这道题将退出复习队列和长期抽查，题目本体保留，可在列表恢复。\n确定斩题吗？")
                        .setPositiveButton("斩题", (dialog, which) -> {
                            QuestionStorage.excludeQuestion(this, currentQuestion);
                            Toast.makeText(this, "已斩题，不再进入复习", Toast.LENGTH_SHORT).show();
                            loadQuestionData();
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });

        // 删除本题
        findViewById(R.id.btn_delete).setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("确认删除")
                    .setMessage("确定要删除这道题吗？\n删除后无法恢复！")
                    .setPositiveButton("删除", (dialog, which) -> {
                        QuestionStorage.delete(this, currentQuestion);
                        Toast.makeText(this, "题目已删除", Toast.LENGTH_SHORT).show();
                        finish();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        QuestionStorage.flush(this);   // 「重学/删除」之后的改动在离开页面前落盘
    }

    // 页面重新显示时 → 自动刷新
    @Override
    protected void onResume() {
        super.onResume();
        loadQuestionData(); // 关键：每次回来都重新加载
    }

    // 抽取成方法，方便刷新
    private void loadQuestionData() {
        currentQuestion = QuestionStorage.findById(this, currentQid);

        if (currentQuestion == null) {
            tvQuestion.setText("题目不存在");
            return;
        }

        tvQuestion.setText("【等级：" + currentQuestion.status + "】"
                + (currentQuestion.excluded ? "【已斩】" : "")
                + "\n题目：\n" + currentQuestion.content);
        tvAnswer.setText(currentQuestion.answer);
        answerLayout.setVisibility(View.VISIBLE);

        // 斩题 / 恢复按钮状态：已斩题只留「恢复此题」，隐藏「重学」；普通题保留「重学 + 斩题」
        if (currentQuestion.excluded) {
            btnRestudy.setVisibility(View.GONE);
            btnExcludeText.setText("恢复此题");
            btnExclude.setCardBackgroundColor(0xff4caf50);
        } else {
            btnRestudy.setVisibility(View.VISIBLE);
            btnExcludeText.setText("斩题");
            btnExclude.setCardBackgroundColor(0xff616161);
        }

        // 刷新图片
        fullscreenPaths.clear();
        if (currentQuestion.contentImages != null) {
            for (String p : currentQuestion.contentImages) if (p != null && !p.isEmpty()) fullscreenPaths.add(p);
        }
        if (currentQuestion.answerImages != null) {
            for (String p : currentQuestion.answerImages) if (p != null && !p.isEmpty()) fullscreenPaths.add(p);
        }

        int idx = 0;
        reviewImageLayout.removeAllViews();
        if (currentQuestion.contentImages != null) {
            for (String path : currentQuestion.contentImages) if (path != null && !path.isEmpty()) addImage(reviewImageLayout, path, idx++);
        }

        answerImageLayout.removeAllViews();
        if (currentQuestion.answerImages != null) {
            for (String path : currentQuestion.answerImages) if (path != null && !path.isEmpty()) addImage(answerImageLayout, path, idx++);
        }
    }

    private void addImage(LinearLayout parent, String path, int index) {
        ImageView iv = new ImageView(this);
        int size = (int) (280 * getResources().getDisplayMetrics().density);
        iv.setLayoutParams(new LinearLayout.LayoutParams(size, LinearLayout.LayoutParams.WRAP_CONTENT));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setAdjustViewBounds(true);
        ImageLoader.bindThumb(this, iv, path, size);
        iv.setOnClickListener(v -> ImageLoader.openFullscreen(this, fullscreenPaths, index));
        parent.addView(iv);
    }
}
