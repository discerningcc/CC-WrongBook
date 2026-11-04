package com.example.demo;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;

public class AddQuestionActivity extends AppCompatActivity {
    private static final int REQ_CONTENT = 1001;
    private static final int REQ_ANSWER = 1002;

    private EditText etContent, etAnswer;
    private LinearLayout layoutContentImg, layoutAnswerImg;
    private ArrayList<String> contentPaths = new ArrayList<>();
    private ArrayList<String> answerPaths = new ArrayList<>();
    private Question editQuestion = null;

    private TextView titleBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_question);

        titleBar = findViewById(R.id.title_bar);
        etContent = findViewById(R.id.et_content);
        etAnswer = findViewById(R.id.et_answer);
        layoutContentImg = findViewById(R.id.layout_content_images);
        layoutAnswerImg = findViewById(R.id.layout_answer_images);

        findViewById(R.id.btn_add_content_img).setOnClickListener(v -> openGallery(REQ_CONTENT));
        findViewById(R.id.btn_add_answer_img).setOnClickListener(v -> openGallery(REQ_ANSWER));
        findViewById(R.id.btn_save).setOnClickListener(v -> save());

        String editQid = getIntent().getStringExtra("edit_qid");
        if (editQid != null) {
            editQuestion = QuestionStorage.findById(this, editQid);
            if (editQuestion != null) {
                etContent.setText(editQuestion.content);
                etAnswer.setText(editQuestion.answer);

                contentPaths.clear();
                contentPaths.addAll(editQuestion.contentImages);
                for (String path : editQuestion.contentImages) {
                    addImage(layoutContentImg, path);
                }

                answerPaths.clear();
                answerPaths.addAll(editQuestion.answerImages);
                for (String path : editQuestion.answerImages) {
                    addImage(layoutAnswerImg, path);
                }

            }
        }

        // 标题：显示当前所属题库
        String bankName = QuestionStorage.getCurrentBankName(this);
        titleBar.setText(editQuestion != null
                ? "修改题目 · " + bankName
                : "添加题目 · 加入「" + bankName + "」");
    }

    private void openGallery(int req) {
        Intent i = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(i, req);
    }

    // ✅ 这里已修复：补全参数类型
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            try {
                String path = ImageUtils.saveImageToPrivate(this, uri);
                if (requestCode == REQ_CONTENT) {
                    contentPaths.add(path);
                    addImage(layoutContentImg, path);
                } else {
                    answerPaths.add(path);
                    addImage(layoutAnswerImg, path);
                }
            } catch (Exception e) {
                Toast.makeText(this, "图片保存失败", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void addImage(LinearLayout layout, String path) {
        ImageView iv = new ImageView(this);
        iv.setLayoutParams(new LinearLayout.LayoutParams(180, 180));
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setImageURI(Uri.parse("file://" + path));

        iv.setOnClickListener(v -> {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("删除图片")
                    .setMessage("确定删除这张图吗？")
                    .setPositiveButton("删除", (dialog, which) -> {
                        if (layout == layoutContentImg) {
                            contentPaths.remove(path);
                        } else {
                            answerPaths.remove(path);
                        }
                        layout.removeView(iv);
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });

        layout.addView(iv);
    }

    @Override
    protected void onPause() {
        super.onPause();
        QuestionStorage.flush(this);   // 退出页面前把新增/修改的题目写完
    }

    private void save() {
        String content = etContent.getText().toString().trim();
        String answer = etAnswer.getText().toString().trim();

        if (editQuestion != null) {
            editQuestion.content = content;
            editQuestion.answer = answer;
            editQuestion.contentImages.clear();
            editQuestion.contentImages.addAll(contentPaths);
            editQuestion.answerImages.clear();
            editQuestion.answerImages.addAll(answerPaths);

            QuestionStorage.update(this);
            Toast.makeText(this, "修改成功", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Question q = new Question(content, answer);
        q.contentImages.addAll(contentPaths);
        q.answerImages.addAll(answerPaths);
        QuestionStorage.save(this, q);
        Toast.makeText(this, "保存成功（永久）", Toast.LENGTH_SHORT).show();
        finish();
    }
}