package com.example.demo;
import android.widget.Toast;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.List;

public class QuestionDetailActivity extends AppCompatActivity {

    private TextView tvQuestion, tvAnswer, tvLevelBadge, tvExcludedBadge, tvQuestionId, tvEncounter;
    private LinearLayout reviewImageLayout, answerImageLayout, answerLayout, actionGroup;
    private View btnExclude, btnRestudy, btnDelete;
    private TextView btnExcludeText;
    private ImageView btnExcludeIcon;
    private ImageView btnFavorite;
    private Question currentQuestion;
    private final List<String> fullscreenPaths = new ArrayList<>();
    /** 全屏看图是否打开中；旋转重建后据此重弹 */
    private boolean fullscreenOpen;
    private int fullscreenIndex = -1;
    /** 内联缩略图长按菜单当前作用的图片路径；旋转重建后据此重弹，null 表示无 */
    private String inlineMenuPath;
    private String currentQid; // 保存ID，用于刷新
    /** 操作弹窗打开期间防重复点击 */
    private boolean actionBusy;
    /** 打开中的操作弹窗类型；旋转重建后据此重显，null 表示无弹窗 */
    private String pendingDialog;
    private static final String KEY_FS_PATHS = "fullscreen_paths";
    private static final String KEY_FS_INDEX = "fullscreen_index";
    private static final String KEY_FS_SUPP = "fullscreen_supp";
    private static final String KEY_FS_MENU = "fullscreen_menu";
    private static final String KEY_DIALOG = "action_dialog";
    private static final String KEY_INLINE_MENU_PATH = "inline_menu_path";
    private static final String DIALOG_RESTUDY = "restudy";
    private static final String DIALOG_EXCLUDE = "exclude";
    private static final String DIALOG_RESTORE = "restore";
    private static final String DIALOG_DELETE = "delete";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_question_detail);

        tvQuestion = findViewById(R.id.tv_question);
        tvAnswer = findViewById(R.id.tv_answer);
        tvLevelBadge = findViewById(R.id.tv_level_badge);
        tvExcludedBadge = findViewById(R.id.tv_excluded_badge);
        tvQuestionId = findViewById(R.id.tv_question_id);
        tvEncounter = findViewById(R.id.tv_encounter);
        reviewImageLayout = findViewById(R.id.review_image_layout);
        answerLayout = findViewById(R.id.answer_layout);
        answerImageLayout = findViewById(R.id.answer_image_layout);
        actionGroup = findViewById(R.id.action_group);
        btnExclude = findViewById(R.id.btn_exclude);
        btnExcludeText = findViewById(R.id.btn_exclude_text);
        btnExcludeIcon = findViewById(R.id.btn_exclude_icon);
        btnRestudy = findViewById(R.id.btn_restudy);
        btnDelete = findViewById(R.id.btn_delete);
        btnFavorite = findViewById(R.id.btn_favorite);

        // 拿到ID并保存
        currentQid = getIntent().getStringExtra("qid");
        loadQuestionData(); // 加载题目
        restoreFullscreen(savedInstanceState);
        restoreActionDialog(savedInstanceState);
        restoreInlineMenu(savedInstanceState);

        // 修改本题
        findViewById(R.id.btn_edit).setOnClickListener(v -> {
            if (currentQuestion == null || actionBusy) return;
            Intent intent = new Intent(QuestionDetailActivity.this, AddQuestionActivity.class);
            intent.putExtra("edit_qid", currentQid);
            startActivity(intent);
        });

        // 重学本题
        btnRestudy.setOnClickListener(v -> {
            if (currentQuestion == null || actionBusy) return;
            showActionDialog(DIALOG_RESTUDY, "重学本题", "确定要将此题重置为「未掌握」吗？\n将重新进入复习计划。", "确定", this::confirmRestudy);
        });

        // 斩题 / 恢复本题
        btnExclude.setOnClickListener(v -> {
            if (currentQuestion == null || actionBusy) return;
            if (currentQuestion.excluded) {
                showActionDialog(DIALOG_RESTORE, "恢复此题", "确定恢复这道题吗？\n将重置为 E 级，重新进入复习计划。", "恢复", this::confirmRestore);
            } else {
                showActionDialog(DIALOG_EXCLUDE, "斩题", "这道题将退出复习队列和长期抽查，题目本体保留，可在列表恢复。\n确定斩题吗？", "斩题", this::confirmExclude);
            }
        });

        // 删除本题
        btnDelete.setOnClickListener(v -> {
            if (currentQuestion == null || actionBusy) return;
            showActionDialog(DIALOG_DELETE, "确认删除", "确定要删除这道题吗？\n本题引用的图片将一并清理，删除后无法恢复！", "删除", this::confirmDelete);
        });

        // 收藏 / 取消收藏：即时翻转并落盘，不弹确认
        btnFavorite.setOnClickListener(v -> {
            if (currentQuestion == null || actionBusy) return;
            QuestionStorage.toggleFavorite(this, currentQuestion);
            Toast.makeText(this, currentQuestion.favorite ? "已收藏" : "已取消收藏", Toast.LENGTH_SHORT).show();
            loadQuestionData();
        });
    }

    private void confirmRestudy() {
        QuestionStorage.restudyQuestion(this, currentQuestion);
        Toast.makeText(this, "已重置，将重新复习", Toast.LENGTH_SHORT).show();
        loadQuestionData(); // 刷新页面等级显示
    }

    private void confirmRestore() {
        QuestionStorage.restoreQuestion(this, currentQuestion);
        Toast.makeText(this, "已恢复，重新开始复习", Toast.LENGTH_SHORT).show();
        loadQuestionData();
    }

    private void confirmExclude() {
        QuestionStorage.excludeQuestion(this, currentQuestion);
        Toast.makeText(this, "已斩题，不再进入复习", Toast.LENGTH_SHORT).show();
        loadQuestionData();
    }

    private void confirmDelete() {
        QuestionStorage.delete(this, currentQuestion);
        Toast.makeText(this, "题目已删除", Toast.LENGTH_SHORT).show();
        finish();
    }

    /** 确认弹窗：打开期间置忙并记录类型，关闭后复位，避免重复点击叠弹；类型供旋转重建后重显 */
    private void showActionDialog(String kind, String title, String message, String positive, Runnable onConfirm) {
        pendingDialog = kind;
        actionBusy = true;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(positive, (dialog, which) -> {
                    pendingDialog = null;
                    onConfirm.run();
                })
                .setNegativeButton("取消", (dialog, which) -> pendingDialog = null)
                .setOnCancelListener(d -> pendingDialog = null)
                .setOnDismissListener(dialog -> { pendingDialog = null; actionBusy = false; })
                .show();
    }

    /** 旋转重建后按留存类型重显之前打开的确认弹窗；仅系统重建（savedInstanceState 非空）时执行，避免同一次 onCreate 重复弹 */
    private void restoreActionDialog(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;
        pendingDialog = savedInstanceState.getString(KEY_DIALOG);
        if (pendingDialog == null || currentQuestion == null) return;
        switch (pendingDialog) {
            case DIALOG_RESTUDY:
                showActionDialog(DIALOG_RESTUDY, "重学本题", "确定要将此题重置为「未掌握」吗？\n将重新进入复习计划。", "确定", this::confirmRestudy);
                break;
            case DIALOG_RESTORE:
                if (!currentQuestion.excluded) { pendingDialog = null; break; }
                showActionDialog(DIALOG_RESTORE, "恢复此题", "确定恢复这道题吗？\n将重置为 E 级，重新进入复习计划。", "恢复", this::confirmRestore);
                break;
            case DIALOG_EXCLUDE:
                if (currentQuestion.excluded) { pendingDialog = null; break; }
                showActionDialog(DIALOG_EXCLUDE, "斩题", "这道题将退出复习队列和长期抽查，题目本体保留，可在列表恢复。\n确定斩题吗？", "斩题", this::confirmExclude);
                break;
            case DIALOG_DELETE:
                showActionDialog(DIALOG_DELETE, "确认删除", "确定要删除这道题吗？\n本题引用的图片将一并清理，删除后无法恢复！", "删除", this::confirmDelete);
                break;
            default:
                pendingDialog = null;
        }
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
            tvAnswer.setText("");
            tvLevelBadge.setVisibility(View.GONE);
            tvExcludedBadge.setVisibility(View.GONE);
            tvQuestionId.setVisibility(View.GONE);
            tvQuestionId.setText("");
            btnFavorite.setVisibility(View.GONE);
            answerLayout.setVisibility(View.GONE);
            actionGroup.setVisibility(View.GONE);
            reviewImageLayout.removeAllViews();
            answerImageLayout.removeAllViews();
            fullscreenPaths.clear();
            return;
        }

        actionGroup.setVisibility(View.VISIBLE);

        // 头部徽章：等级 + 已斩
        String status = currentQuestion.status == null ? "E" : currentQuestion.status;
        tvLevelBadge.setText(status);
        tvLevelBadge.setBackgroundTintList(ColorStateList.valueOf(QuestionStorage.statusColor(status)));
        tvExcludedBadge.setVisibility(currentQuestion.excluded ? View.VISIBLE : View.GONE);
        tvExcludedBadge.setBackgroundTintList(ColorStateList.valueOf(0xff616161));
        tvQuestionId.setVisibility(View.VISIBLE);
        tvQuestionId.setText(currentQuestion.id == null ? "" : currentQuestion.id);

        // S 级长期题：展示累计抽查次数（0 次也显示）；非 S 档隐藏
        if ("S".equals(status)) {
            tvEncounter.setVisibility(View.VISIBLE);
            tvEncounter.setText("抽查 " + currentQuestion.encounterCount + " 次");
        } else {
            tvEncounter.setVisibility(View.GONE);
        }

        btnFavorite.setVisibility(View.VISIBLE);
        btnFavorite.setImageResource(currentQuestion.favorite
                ? R.drawable.ic_star_filled : R.drawable.ic_star_outline);
        btnFavorite.setContentDescription(currentQuestion.favorite ? "取消收藏" : "收藏");

        tvQuestion.setText(currentQuestion.content == null ? "" : currentQuestion.content);
        tvAnswer.setText(currentQuestion.answer == null ? "" : currentQuestion.answer);

        // 答案文本与答案图都为空时隐藏答案块
        boolean hasAnswerText = currentQuestion.answer != null && !currentQuestion.answer.trim().isEmpty();
        boolean hasAnswerImages = currentQuestion.answerImages != null && !currentQuestion.answerImages.isEmpty();
        answerLayout.setVisibility(hasAnswerText || hasAnswerImages ? View.VISIBLE : View.GONE);

        // 斩题 / 恢复按钮状态：已斩题只留「恢复」，隐藏「重学」；普通题保留「重学 + 斩题」
        int secondaryText = getColor(R.color.detail_text_secondary);
        int success = getColor(R.color.detail_success);
        if (currentQuestion.excluded) {
            btnRestudy.setVisibility(View.GONE);
            btnExcludeText.setText("恢复");
            btnExcludeText.setTextColor(success);
            btnExclude.setBackgroundResource(R.drawable.bg_detail_outline_success);
            btnExcludeIcon.setImageResource(R.drawable.ic_detail_restore);
            btnExcludeIcon.setContentDescription("恢复");
        } else {
            btnRestudy.setVisibility(View.VISIBLE);
            btnExcludeText.setText("斩题");
            btnExcludeText.setTextColor(secondaryText);
            btnExclude.setBackgroundResource(R.drawable.bg_detail_outline_gray);
            btnExcludeIcon.setImageResource(R.drawable.ic_detail_exclude);
            btnExcludeIcon.setContentDescription("斩题");
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
        iv.setOnClickListener(v -> openFullscreenFromDetail(index));
        iv.setOnLongClickListener(v -> {
            inlineMenuPath = path;
            ImageLoader.showImageMenu(this, path, null);
            return true;
        });
        parent.addView(iv);
    }

    /** 打开全屏看图并记录留存状态；弹窗关闭时清掉打开标记 */
    private void openFullscreenFromDetail(int index) {
        openFullscreenFromDetail(index, null, false);
    }

    private void openFullscreenFromDetail(int index, float[] supp) {
        openFullscreenFromDetail(index, supp, false);
    }

    private void openFullscreenFromDetail(int index, float[] supp, boolean menuOpen) {
        fullscreenOpen = true;
        fullscreenIndex = index;
        ImageLoader.openFullscreen(this, fullscreenPaths, index, () -> fullscreenOpen = false, supp, menuOpen);
    }

    /** 旋转重建后重弹之前打开的全屏看图；仅系统重建（savedInstanceState 非空）时执行 */
    private void restoreFullscreen(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;
        ArrayList<String> paths = savedInstanceState.getStringArrayList(KEY_FS_PATHS);
        if (paths == null || paths.isEmpty()) return;
        fullscreenPaths.clear();
        fullscreenPaths.addAll(paths);
        fullscreenIndex = savedInstanceState.getInt(KEY_FS_INDEX, 0);
        boolean menuOpen = savedInstanceState.getBoolean(KEY_FS_MENU, false);
        openFullscreenFromDetail(fullscreenIndex, savedInstanceState.getFloatArray(KEY_FS_SUPP), menuOpen);
    }

    /** 旋转重建后重显内联缩略图长按菜单；仅系统重建（savedInstanceState 非空）时执行，避免同一次 onCreate 叠弹 */
    private void restoreInlineMenu(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;
        String p = savedInstanceState.getString(KEY_INLINE_MENU_PATH);
        if (p == null) return;
        inlineMenuPath = p;
        findViewById(android.R.id.content).post(() -> {
            if (!isFinishing()) ImageLoader.showImageMenu(this, p, null);
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(KEY_DIALOG, pendingDialog);
        if (fullscreenOpen) {
            outState.putStringArrayList(KEY_FS_PATHS, new ArrayList<>(fullscreenPaths));
            outState.putInt(KEY_FS_INDEX, fullscreenIndex);
            outState.putFloatArray(KEY_FS_SUPP, ImageLoader.captureSuppMatrix());
            outState.putBoolean(KEY_FS_MENU, ImageLoader.captureSaveMenuOpen());
        }
        if (inlineMenuPath != null && ImageLoader.captureSaveMenuOpen()) {
            outState.putString(KEY_INLINE_MENU_PATH, inlineMenuPath);
        }
    }
}
