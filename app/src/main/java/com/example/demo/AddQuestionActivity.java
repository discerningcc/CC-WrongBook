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
    private static final String KEY_DISCARD_OPEN = "discard_open";
    private static final String KEY_DELETE_GROUP = "delete_group";
    private static final String KEY_DELETE_PATH = "delete_path";
    private static final String KEY_FS_OPEN = "fs_open";
    private static final String KEY_FS_GROUP = "fs_group";
    private static final String KEY_FS_INDEX = "fs_index";
    private static final String KEY_FS_SUPP = "fs_supp";
    private static final String KEY_FS_MENU = "fs_menu";
    /** 每组图片上限 */
    private static final int MAX_IMAGES = 9;
    /** 图片分组：0 题干图，1 答案图 */
    private static final int GROUP_CONTENT = 0;
    private static final int GROUP_ANSWER = 1;

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

    // 跨旋转留存的临时弹窗/看图状态
    private boolean discardConfirmOpen;
    private int deleteGroup = -1;
    private String deletePath;
    private boolean fullscreenOpen;
    private int fsGroup = -1;
    private int fsIndex = -1;

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
            for (String path : contentPaths) addImage(layoutContentImg, GROUP_CONTENT, path);
            for (String path : answerPaths) addImage(layoutAnswerImg, GROUP_ANSWER, path);
            if (editQid != null) editQuestion = QuestionStorage.findById(this, editQid);

            discardConfirmOpen = savedInstanceState.getBoolean(KEY_DISCARD_OPEN, false);
            deleteGroup = savedInstanceState.getInt(KEY_DELETE_GROUP, -1);
            deletePath = savedInstanceState.getString(KEY_DELETE_PATH);
            fullscreenOpen = savedInstanceState.getBoolean(KEY_FS_OPEN, false);
            fsGroup = savedInstanceState.getInt(KEY_FS_GROUP, -1);
            fsIndex = savedInstanceState.getInt(KEY_FS_INDEX, -1);
        } else if (editQid != null) {
            editQuestion = QuestionStorage.findById(this, editQid);
            if (editQuestion != null) {
                etContent.setText(editQuestion.content);
                etAnswer.setText(editQuestion.answer);

                contentPaths.clear();
                contentPaths.addAll(editQuestion.contentImages);
                for (String path : editQuestion.contentImages) {
                    addImage(layoutContentImg, GROUP_CONTENT, path);
                }

                answerPaths.clear();
                answerPaths.addAll(editQuestion.answerImages);
                for (String path : editQuestion.answerImages) {
                    addImage(layoutAnswerImg, GROUP_ANSWER, path);
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

        restoreTransientState(savedInstanceState);
    }

    /** 旋转重建后恢复临时弹窗与大图预览；仅系统重建（savedInstanceState 非空）时执行 */
    private void restoreTransientState(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;
        if (discardConfirmOpen) showDiscardConfirm();
        if (deletePath != null) confirmDelete(deleteGroup, deletePath);
        if (fullscreenOpen && fsGroup >= 0 && fsIndex >= 0) {
            List<String> list = fsGroup == GROUP_CONTENT ? contentPaths : answerPaths;
            float[] supp = savedInstanceState.getFloatArray(KEY_FS_SUPP);
            boolean menuOpen = savedInstanceState.getBoolean(KEY_FS_MENU, false);
            openPreview(fsGroup, fsIndex, list, supp, menuOpen);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putStringArrayList(KEY_CONTENT_IMGS, contentPaths);
        outState.putStringArrayList(KEY_ANSWER_IMGS, answerPaths);
        outState.putBoolean(KEY_DISCARD_OPEN, discardConfirmOpen);
        outState.putInt(KEY_DELETE_GROUP, deleteGroup);
        outState.putString(KEY_DELETE_PATH, deletePath);
        outState.putBoolean(KEY_FS_OPEN, fullscreenOpen);
        outState.putInt(KEY_FS_GROUP, fsGroup);
        outState.putInt(KEY_FS_INDEX, fsIndex);
        if (fullscreenOpen) {
            outState.putFloatArray(KEY_FS_SUPP, ImageLoader.captureSuppMatrix());
            outState.putBoolean(KEY_FS_MENU, ImageLoader.captureSaveMenuOpen());
        }
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
                addImage(layoutContentImg, GROUP_CONTENT, path);
            } else {
                answerPaths.add(path);
                addImage(layoutAnswerImg, GROUP_ANSWER, path);
            }
        } catch (Exception e) {
            Toast.makeText(this, "图片保存失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void addImage(LinearLayout layout, int group, String path) {
        ImageView iv = new ImageView(this);
        int size = (int) (180 * getResources().getDisplayMetrics().density);
        iv.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setTag(path);
        ImageLoader.bindThumb(this, iv, path, size);

        // 点击先进入大图预览
        iv.setOnClickListener(v -> {
            List<String> list = group == GROUP_CONTENT ? contentPaths : answerPaths;
            int idx = Math.max(0, layout.indexOfChild(iv));
            openPreview(group, idx, list, null, false);
        });
        // 长按弹出删除确认
        iv.setOnLongClickListener(v -> {
            confirmDelete(group, path);
            return true;
        });

        layout.addView(iv);
        refreshCounts();
    }

    private void confirmDelete(int group, String path) {
        deleteGroup = group;
        deletePath = path;
        LinearLayout layout = group == GROUP_CONTENT ? layoutContentImg : layoutAnswerImg;
        new AlertDialog.Builder(this)
                .setTitle("删除图片")
                .setMessage("确定删除这张图吗？")
                .setOnDismissListener(d -> {
                    deleteGroup = -1;
                    deletePath = null;
                })
                .setPositiveButton("删除", (dialog, which) -> {
                    boolean wasBaseline = initContentImgs.contains(path) || initAnswerImgs.contains(path);
                    if (group == GROUP_CONTENT) {
                        contentPaths.remove(path);
                    } else {
                        answerPaths.remove(path);
                    }
                    // 按路径找回对应缩略图并移除
                    for (int i = 0; i < layout.getChildCount(); i++) {
                        View child = layout.getChildAt(i);
                        if (path.equals(child.getTag())) {
                            layout.removeViewAt(i);
                            break;
                        }
                    }
                    // 本次新加后又删掉的图，立即清理私有文件；基线图的移除留到保存时统一比对
                    if (!wasBaseline) {
                        QuestionStorage.deletePrivateImageIfUnused(this, path);
                    }
                    refreshCounts();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 打开大图预览并记录留存状态；弹窗关闭时清掉打开标记 */
    private void openPreview(int group, int index, List<String> list, float[] restoreSupp, boolean restoreMenu) {
        fullscreenOpen = true;
        fsGroup = group;
        fsIndex = index;
        ImageLoader.openFullscreen(this, list, index, () -> fullscreenOpen = false,
                restoreSupp, restoreMenu);
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
            showDiscardConfirm();
            return;
        }
        super.onBackPressed();
    }

    private void showDiscardConfirm() {
        discardConfirmOpen = true;
        new AlertDialog.Builder(this)
                .setTitle("放弃当前编辑")
                .setMessage("内容尚未保存，确定放弃吗？")
                .setOnDismissListener(d -> discardConfirmOpen = false)
                .setPositiveButton("放弃", (dialog, which) -> finish())
                .setNegativeButton("继续编辑", null)
                .show();
    }
}
