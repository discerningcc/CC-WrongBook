package com.example.demo;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.List;

public class AddQuestionActivity extends AppCompatActivity {
    private static final int REQ_CONTENT = 1001;
    private static final int REQ_ANSWER = 1002;
    private static final String KEY_CONTENT_IMGS = "content_images";
    private static final String KEY_ANSWER_IMGS = "answer_images";
    /** 每组图片上限 */
    private static final int MAX_IMAGES = 9;

    private EditText etContent, etAnswer;
    private LinearLayout layoutContentImg, layoutAnswerImg;
    private TextView tvContentCount, tvAnswerCount;
    private View btnSaveContinue;
    private ArrayList<String> contentPaths = new ArrayList<>();
    private ArrayList<String> answerPaths = new ArrayList<>();
    private Question editQuestion = null;

    private TextView titleBar;

    // 进入页面时的基线内容，用于判断是否有未保存改动
    private String initContent, initAnswer;
    private ArrayList<String> initContentImgs, initAnswerImgs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_question);

        titleBar = findViewById(R.id.title_bar);
        etContent = findViewById(R.id.et_content);
        etAnswer = findViewById(R.id.et_answer);
        layoutContentImg = findViewById(R.id.layout_content_images);
        layoutAnswerImg = findViewById(R.id.layout_answer_images);
        tvContentCount = findViewById(R.id.tv_content_count);
        tvAnswerCount = findViewById(R.id.tv_answer_count);
        btnSaveContinue = findViewById(R.id.btn_save_continue);

        findViewById(R.id.btn_back).setOnClickListener(v -> onBackPressed());
        findViewById(R.id.btn_add_content_img).setOnClickListener(v -> openGallery(REQ_CONTENT));
        findViewById(R.id.btn_add_answer_img).setOnClickListener(v -> openGallery(REQ_ANSWER));
        findViewById(R.id.btn_save).setOnClickListener(v -> save(false));
        btnSaveContinue.setOnClickListener(v -> save(true));

        String editQid = getIntent().getStringExtra("edit_qid");
        if (savedInstanceState != null) {
            // 旋转重建：恢复已选图片路径与编辑对象；输入框文本由系统按 View id 自动恢复
            contentPaths = savedInstanceState.getStringArrayList(KEY_CONTENT_IMGS);
            answerPaths = savedInstanceState.getStringArrayList(KEY_ANSWER_IMGS);
            if (contentPaths == null) contentPaths = new ArrayList<>();
            if (answerPaths == null) answerPaths = new ArrayList<>();
            for (String path : contentPaths) addImage(layoutContentImg, path);
            for (String path : answerPaths) addImage(layoutAnswerImg, path);
            if (editQid != null) editQuestion = QuestionStorage.findById(this, editQid);
        } else if (editQid != null) {
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

        snapshotBaseline();
        refreshCounts();
        // 编辑态不提供「保存并继续」
        btnSaveContinue.setVisibility(editQuestion != null ? View.GONE : View.VISIBLE);

        // 标题：显示当前所属题库
        String bankName = QuestionStorage.getCurrentBankName(this);
        titleBar.setText(editQuestion != null
                ? "修改题目 · " + bankName
                : "添加题目 · 加入「" + bankName + "」");
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putStringArrayList(KEY_CONTENT_IMGS, contentPaths);
        outState.putStringArrayList(KEY_ANSWER_IMGS, answerPaths);
    }

    /** 记录进入/上一次保存后的基线，供脏状态判定 */
    private void snapshotBaseline() {
        initContent = etContent.getText().toString();
        initAnswer = etAnswer.getText().toString();
        initContentImgs = new ArrayList<>(contentPaths);
        initAnswerImgs = new ArrayList<>(answerPaths);
    }

    private boolean isDirty() {
        String content = etContent.getText().toString();
        String answer = etAnswer.getText().toString();
        return !content.equals(initContent) || !answer.equals(initAnswer)
                || !contentPaths.equals(initContentImgs) || !answerPaths.equals(initAnswerImgs);
    }

    private void refreshCounts() {
        tvContentCount.setText(contentPaths.size() + "/" + MAX_IMAGES);
        tvAnswerCount.setText(answerPaths.size() + "/" + MAX_IMAGES);
    }

    private void openGallery(int req) {
        Intent i = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(i, req);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        ArrayList<String> target = requestCode == REQ_CONTENT ? contentPaths : answerPaths;
        if (target.size() >= MAX_IMAGES) {
            Toast.makeText(this, "每组最多 " + MAX_IMAGES + " 张图片", Toast.LENGTH_SHORT).show();
            return;
        }
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

    private void addImage(LinearLayout layout, String path) {
        ImageView iv = new ImageView(this);
        int size = (int) (180 * getResources().getDisplayMetrics().density);
        iv.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ImageLoader.bindThumb(this, iv, path, size);

        // 点击先进入大图预览
        iv.setOnClickListener(v -> {
            List<String> list = layout == layoutContentImg ? contentPaths : answerPaths;
            int idx = Math.max(0, layout.indexOfChild(iv));
            ImageLoader.openFullscreen(this, list, idx);
        });
        // 长按弹出删除确认
        iv.setOnLongClickListener(v -> {
            confirmDelete(layout, iv, path);
            return true;
        });

        layout.addView(iv);
        refreshCounts();
    }

    private void confirmDelete(LinearLayout layout, ImageView iv, String path) {
        new AlertDialog.Builder(this)
                .setTitle("删除图片")
                .setMessage("确定删除这张图吗？")
                .setPositiveButton("删除", (dialog, which) -> {
                    boolean wasBaseline = initContentImgs.contains(path) || initAnswerImgs.contains(path);
                    if (layout == layoutContentImg) {
                        contentPaths.remove(path);
                    } else {
                        answerPaths.remove(path);
                    }
                    layout.removeView(iv);
                    // 本次新加后又删掉的图，立即清理私有文件；基线图的移除留到保存时统一比对
                    if (!wasBaseline) {
                        QuestionStorage.deletePrivateImageIfUnused(this, path);
                    }
                    refreshCounts();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        QuestionStorage.flush(this);   // 退出页面前把新增/修改的题目写完
    }

    private void save(boolean andContinue) {
        String content = etContent.getText().toString().trim();
        String answer = etAnswer.getText().toString().trim();

        // 校验：题干/答案文本与两组图片中，任意一项非空即可；全空拦截
        if (content.isEmpty() && answer.isEmpty()
                && contentPaths.isEmpty() && answerPaths.isEmpty()) {
            Toast.makeText(this, "请至少填写题目或答案，或添加一张图片", Toast.LENGTH_SHORT).show();
            return;
        }

        // 本次被移除的图片：保存成功后清理未被其他题目引用的私有文件
        List<String> removed = new ArrayList<>();
        for (String p : initContentImgs) {
            if (!contentPaths.contains(p)) removed.add(p);
        }
        for (String p : initAnswerImgs) {
            if (!answerPaths.contains(p)) removed.add(p);
        }

        if (editQuestion != null) {
            editQuestion.content = content;
            editQuestion.answer = answer;
            editQuestion.contentImages.clear();
            editQuestion.contentImages.addAll(contentPaths);
            editQuestion.answerImages.clear();
            editQuestion.answerImages.addAll(answerPaths);

            QuestionStorage.update(this);
            for (String p : removed) QuestionStorage.deletePrivateImageIfUnused(this, p);
            Toast.makeText(this, "修改已保存", Toast.LENGTH_SHORT).show();
            if (andContinue) {
                resetForm();
            } else {
                finish();
            }
            return;
        }

        Question q = new Question(content, answer);
        q.contentImages.addAll(contentPaths);
        q.answerImages.addAll(answerPaths);
        QuestionStorage.save(this, q);
        for (String p : removed) QuestionStorage.deletePrivateImageIfUnused(this, p);
        Toast.makeText(this, "题目已保存", Toast.LENGTH_SHORT).show();
        if (andContinue) {
            resetForm();
        } else {
            finish();
        }
    }

    /** 保存并继续：清空表单留在本页，准备录入下一题 */
    private void resetForm() {
        etContent.setText("");
        etAnswer.setText("");
        contentPaths.clear();
        answerPaths.clear();
        layoutContentImg.removeAllViews();
        layoutAnswerImg.removeAllViews();
        snapshotBaseline();
        refreshCounts();
    }

    @Override
    public void onBackPressed() {
        if (isDirty()) {
            new AlertDialog.Builder(this)
                    .setTitle("放弃当前编辑")
                    .setMessage("内容尚未保存，确定放弃吗？")
                    .setPositiveButton("放弃", (dialog, which) -> finish())
                    .setNegativeButton("继续编辑", null)
                    .show();
            return;
        }
        super.onBackPressed();
    }
}
