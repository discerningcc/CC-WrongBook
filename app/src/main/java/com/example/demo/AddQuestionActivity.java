package com.example.demo;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.Gson;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AddQuestionActivity extends AppCompatActivity {
    private static final String TAG = "AddQuestionActivity";
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
    /** 草稿文件名（仅新增态使用） */
    private static final String DRAFT_FILE = "draft_add_question.json";

    private EditText etContent, etAnswer;
    private RecyclerView rvContent, rvAnswer;
    private TextView tvContentCount, tvAnswerCount;
    private View btnSaveContinue;
    private ArrayList<String> contentPaths = new ArrayList<>();
    private ArrayList<String> answerPaths = new ArrayList<>();
    private Question editQuestion = null;

    private ThumbAdapter adapterContent, adapterAnswer;
    private ItemTouchHelper helperContent, helperAnswer;

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
    /** 用户确认放弃后置位，抑制 onPause 兜底再次落草稿 */
    private boolean discarding = false;
    /** 新题保存并退出后置位，抑制 onPause 兜底把已落库表单回写成草稿 */
    private boolean savedFinishing = false;
    /** 是否由上次草稿回填进入（回填内容按未保存改动处理，返回需确认放弃） */
    private boolean draftRestored = false;

    private final ExecutorService BG = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "add-question-bg");
        t.setDaemon(true);
        return t;
    });
    private final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 相册取图：题干图（ACTION_PICK 直接进相册，而非文件管理器） */
    private final ActivityResultLauncher<Intent> pickContent = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> onPicked(GROUP_CONTENT, result.getData() != null ? result.getData().getData() : null));
    /** 相册取图：答案图 */
    private final ActivityResultLauncher<Intent> pickAnswer = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> onPicked(GROUP_ANSWER, result.getData() != null ? result.getData().getData() : null));

    /** 构造系统相册选图 Intent；无可用相册时返回 null 由调用方兜底提示。 */
    private Intent galleryIntent() {
        Intent i = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        return i;
    }

    private void launchGallery(ActivityResultLauncher<Intent> launcher) {
        try {
            launcher.launch(galleryIntent());
        } catch (Exception e) {
            Toast.makeText(this, "无法打开相册", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_question);

        titleBar = findViewById(R.id.title_bar);
        etContent = findViewById(R.id.et_content);
        etAnswer = findViewById(R.id.et_answer);
        rvContent = findViewById(R.id.rv_content_images);
        rvAnswer = findViewById(R.id.rv_answer_images);
        tvContentCount = findViewById(R.id.tv_content_count);
        tvAnswerCount = findViewById(R.id.tv_answer_count);
        btnSaveContinue = findViewById(R.id.btn_save_continue);

        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        int spanCount = landscape ? 4 : 3;
        rvContent.setLayoutManager(new GridLayoutManager(this, spanCount));
        rvAnswer.setLayoutManager(new GridLayoutManager(this, spanCount));

        findViewById(R.id.btn_back).setOnClickListener(v -> onBackPressed());
        findViewById(R.id.btn_add_content_img).setOnClickListener(v -> launchGallery(pickContent));
        findViewById(R.id.btn_add_answer_img).setOnClickListener(v -> launchGallery(pickAnswer));
        findViewById(R.id.btn_save).setOnClickListener(v -> save(false));
        btnSaveContinue.setOnClickListener(v -> save(true));

        etContent.addTextChangedListener(draftWatcher);
        etAnswer.addTextChangedListener(draftWatcher);

        String editQid = getIntent().getStringExtra("edit_qid");
        if (savedInstanceState != null) {
            // 旋转重建：恢复已选图片路径与编辑对象；输入框文本由系统按 View id 自动恢复
            contentPaths = savedInstanceState.getStringArrayList(KEY_CONTENT_IMGS);
            answerPaths = savedInstanceState.getStringArrayList(KEY_ANSWER_IMGS);
            if (contentPaths == null) contentPaths = new ArrayList<>();
            if (answerPaths == null) answerPaths = new ArrayList<>();
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
                answerPaths.clear();
                answerPaths.addAll(editQuestion.answerImages);
            }
        } else {
            // 新增态：回填上次未保存的草稿
            draftRestored = loadDraft();
        }

        adapterContent = new ThumbAdapter(GROUP_CONTENT, contentPaths);
        adapterAnswer = new ThumbAdapter(GROUP_ANSWER, answerPaths);
        helperContent = makeHelper(adapterContent);
        helperAnswer = makeHelper(adapterAnswer);
        helperContent.attachToRecyclerView(rvContent);
        helperAnswer.attachToRecyclerView(rvAnswer);
        rvContent.setAdapter(adapterContent);
        rvAnswer.setAdapter(adapterAnswer);

        snapshotBaseline();
        if (draftRestored) resetBaselineToEmpty();
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

    /** 草稿回填时把基线置空：回填内容视为未保存改动，返回需确认放弃 */
    private void resetBaselineToEmpty() {
        initContent = "";
        initAnswer = "";
        initContentImgs = new ArrayList<>();
        initAnswerImgs = new ArrayList<>();
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

    private ThumbAdapter adapterOf(int group) {
        return group == GROUP_CONTENT ? adapterContent : adapterAnswer;
    }

    // ==================== 相册取图（Activity Result API） ====================

    private void onPicked(int group, Uri uri) {
        if (uri == null) return;
        ArrayList<String> target = group == GROUP_CONTENT ? contentPaths : answerPaths;
        if (target.size() >= MAX_IMAGES) {
            Toast.makeText(this, "每组最多 " + MAX_IMAGES + " 张图片", Toast.LENGTH_SHORT).show();
            return;
        }
        BG.execute(() -> {
            String path;
            try {
                path = ImageUtils.saveImageToPrivate(this, uri);
            } catch (Exception e) {
                MAIN.post(() -> Toast.makeText(this, "图片保存失败", Toast.LENGTH_SHORT).show());
                return;
            }
            MAIN.post(() -> {
                target.add(path);
                adapterOf(group).notifyItemInserted(target.size() - 1);
                refreshCounts();
                saveDraft();
            });
        });
    }

    // ==================== 删除 ====================

    private void confirmDelete(int group, String path) {
        deleteGroup = group;
        deletePath = path;
        new AlertDialog.Builder(this)
                .setTitle("删除图片")
                .setMessage("确定删除这张图吗？")
                .setOnDismissListener(d -> {
                    deleteGroup = -1;
                    deletePath = null;
                })
                .setPositiveButton("删除", (dialog, which) -> doDelete(group, path))
                .setNegativeButton("取消", null)
                .show();
    }

    private void doDelete(int group, String path) {
        boolean wasBaseline = initContentImgs.contains(path) || initAnswerImgs.contains(path);
        ArrayList<String> target = group == GROUP_CONTENT ? contentPaths : answerPaths;
        int idx = target.indexOf(path);
        if (idx >= 0) {
            target.remove(idx);
            adapterOf(group).notifyItemRemoved(idx);
        }
        refreshCounts();
        saveDraft();
        // 本次新加后又删掉的图，立即清理私有文件；基线图的移除留到保存时统一比对
        if (!wasBaseline) {
            QuestionStorage.deletePrivateImageIfUnused(this, path);
        }
        // 若从大图内触发删除，同步把看图当前页移除；组内清空由组件关闭看图
        if (fullscreenOpen) ImageLoader.removeCurrentViewerImage();
    }

    /** 大图内「删除当前图」回调：复用本组删图确认口径 */
    private void onViewerDeleteRequested(String path) {
        if (fsGroup < 0) return;
        confirmDelete(fsGroup, path);
    }

    /** 打开大图预览并记录留存状态；弹窗关闭时清掉打开标记 */
    private void openPreview(int group, int index, List<String> list, float[] restoreSupp, boolean restoreMenu) {
        fullscreenOpen = true;
        fsGroup = group;
        fsIndex = index;
        ImageLoader.openFullscreen(this, list, index, () -> fullscreenOpen = false,
                restoreSupp, restoreMenu, this::onViewerDeleteRequested);
    }

    // ==================== 草稿持久化（仅新增态） ====================

    private final TextWatcher draftWatcher = new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
        @Override public void afterTextChanged(Editable s) { saveDraft(); }
    };

    private File draftFile() {
        return new File(getFilesDir(), DRAFT_FILE);
    }

    private List<String> combinedPaths() {
        List<String> all = new ArrayList<>();
        all.addAll(contentPaths);
        all.addAll(answerPaths);
        return all;
    }

    /** 新增态把当前文本与图片路径写入私有草稿；全空则清除草稿。编辑既有题不写草稿。 */
    private void saveDraft() {
        if (editQuestion != null || discarding || savedFinishing) return;
        String content = etContent.getText().toString();
        String answer = etAnswer.getText().toString();
        boolean empty = content.trim().isEmpty() && answer.trim().isEmpty()
                && contentPaths.isEmpty() && answerPaths.isEmpty();
        if (empty) {
            clearDraft();
            return;
        }
        final Draft draft = new Draft();
        draft.content = content;
        draft.answer = answer;
        draft.contentImages = new ArrayList<>(contentPaths);
        draft.answerImages = new ArrayList<>(answerPaths);
        BG.execute(() -> {
            try (FileWriter fw = new FileWriter(draftFile())) {
                new Gson().toJson(draft, fw);
            } catch (Exception e) {
                Log.w(TAG, "save draft failed", e);
            }
        });
        QuestionStorage.retainDraftImages(combinedPaths());
    }

    /** 进入新增态时回填草稿；草稿图登记到保留集合，避免被孤儿清理误删。返回是否回填了非空草稿。 */
    private boolean loadDraft() {
        File f = draftFile();
        if (!f.exists()) return false;
        try (FileReader fr = new FileReader(f)) {
            Draft draft = new Gson().fromJson(fr, Draft.class);
            if (draft == null) return false;
            etContent.setText(draft.content == null ? "" : draft.content);
            etAnswer.setText(draft.answer == null ? "" : draft.answer);
            contentPaths.clear();
            answerPaths.clear();
            if (draft.contentImages != null) contentPaths.addAll(draft.contentImages);
            if (draft.answerImages != null) answerPaths.addAll(draft.answerImages);
        } catch (Exception e) {
            Log.w(TAG, "parse draft failed", e);
            return false;
        }
        QuestionStorage.retainDraftImages(combinedPaths());
        return !etContent.getText().toString().trim().isEmpty()
                || !etAnswer.getText().toString().trim().isEmpty()
                || !contentPaths.isEmpty() || !answerPaths.isEmpty();
    }

    /** 清除草稿文件并解除草稿图保留登记。 */
    private void clearDraft() {
        File f = draftFile();
        if (f.exists()) f.delete();
        QuestionStorage.clearDraftRetained();
    }

    private static class Draft {
        String content;
        String answer;
        List<String> contentImages;
        List<String> answerImages;
    }

    @Override
    protected void onPause() {
        super.onPause();
        QuestionStorage.flush(this);   // 退出页面前把新增/修改的题目写完
        saveDraft();                   // 兜底落一次草稿
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
        // 新题保存成功，草稿已落库，清除草稿
        clearDraft();
        Toast.makeText(this, "题目已保存", Toast.LENGTH_SHORT).show();
        if (andContinue) {
            resetForm();
        } else {
            savedFinishing = true;
            finish();
        }
    }

    /** 保存并继续：清空表单留在本页，准备录入下一题 */
    private void resetForm() {
        etContent.setText("");
        etAnswer.setText("");
        contentPaths.clear();
        answerPaths.clear();
        adapterContent.notifyDataSetChanged();
        adapterAnswer.notifyDataSetChanged();
        snapshotBaseline();
        refreshCounts();
        clearDraft();
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
                .setPositiveButton("放弃", (dialog, which) -> {
                    discarding = true;
                    // 本次新选、未保存进题库的图片随草稿一并删除，避免残留孤儿文件；基线既有图不在此列
                    List<String> unsaved = new ArrayList<>();
                    for (String p : combinedPaths()) {
                        if (!initContentImgs.contains(p) && !initAnswerImgs.contains(p)) unsaved.add(p);
                    }
                    clearDraft();
                    for (String p : unsaved) {
                        QuestionStorage.deletePrivateImageIfUnused(this, p);
                    }
                    finish();
                })
                .setNegativeButton("继续编辑", null)
                .show();
    }

    // ==================== 网格适配器 + 直接拖拽 ====================

    private ItemTouchHelper makeHelper(ThumbAdapter adapter) {
        ItemTouchHelper.Callback callback = new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN
                        | ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT, 0) {

            @Override
            public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder from, RecyclerView.ViewHolder to) {
                adapter.swap(from.getAdapterPosition(), to.getAdapterPosition());
                return true;
            }

            @Override
            public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
                // 不支持滑动删除
            }

            @Override
            public boolean isLongPressDragEnabled() {
                // 直接拖拽：关闭默认长按起拖，由触摸滑距判定后手动 startDrag
                return false;
            }
        };
        return new ItemTouchHelper(callback);
    }

    private final class ThumbAdapter extends RecyclerView.Adapter<ThumbAdapter.VH> {
        private final int group;
        private final List<String> paths;

        ThumbAdapter(int group, List<String> paths) {
            this.group = group;
            this.paths = paths;
        }

        @Override
        public VH onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            View root = getLayoutInflater().inflate(R.layout.item_add_thumb, parent, false);
            return new VH(root);
        }

        @Override
        public void onBindViewHolder(VH holder, int position) {
            String path = paths.get(position);
            holder.iv.setTag(path);
            ImageLoader.bindThumb(AddQuestionActivity.this, holder.iv, path, 300);

            holder.itemView.setOnClickListener(v -> {
                int idx = holder.getAdapterPosition();
                if (idx < 0) return;
                openPreview(group, idx, paths, null, false);
            });
            holder.itemView.setOnLongClickListener(v -> {
                int idx = holder.getAdapterPosition();
                if (idx < 0) return true;
                confirmDelete(group, paths.get(idx));
                return true;
            });
            attachDirectDrag(holder);
        }

        @Override
        public int getItemCount() {
            return paths.size();
        }

        void swap(int from, int to) {
            if (from < 0 || to < 0 || from >= paths.size() || to >= paths.size()) return;
            Collections.swap(paths, from, to);
            notifyItemMoved(from, to);
            saveDraft();
        }

        private void attachDirectDrag(VH holder) {
            final float slop = ViewConfiguration.get(AddQuestionActivity.this).getScaledTouchSlop();
            final float[] down = new float[2];
            final boolean[] started = {false};
            holder.itemView.setOnTouchListener((v, ev) -> {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        down[0] = ev.getX();
                        down[1] = ev.getY();
                        started[0] = false;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (!started[0]
                                && Math.hypot(ev.getX() - down[0], ev.getY() - down[1]) > slop) {
                            started[0] = true;
                            ItemTouchHelper helper = group == GROUP_CONTENT ? helperContent : helperAnswer;
                            if (helper != null) helper.startDrag(holder);
                        }
                        break;
                    default:
                        break;
                }
                return false;
            });
        }

        final class VH extends RecyclerView.ViewHolder {
            final ImageView iv;

            VH(View root) {
                super(root);
                iv = root.findViewById(R.id.thumb);
            }
        }
    }
}
