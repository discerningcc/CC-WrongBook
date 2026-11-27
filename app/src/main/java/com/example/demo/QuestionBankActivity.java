package com.example.demo;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SearchView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class QuestionBankActivity extends AppCompatActivity {

    // 类别胶囊：可多选叠加（同时满足），mask 为 0 表示「全部」
    private static final int CAT_DUE = 1 << 1;
    private static final int CAT_EXCLUDED = 1 << 2;
    private static final int CAT_NOT_EXCLUDED = 1 << 3;
    private static final int CAT_FAVORITE = 1 << 4;

    private static final String[] CAT_LABELS = {"全部", "待复习", "已斩", "未斩", "收藏"};
    private static final String[] LEVEL_LABELS = {"S", "A", "B", "C", "D", "E"};
    private static final long SEARCH_DEBOUNCE_MS = 250;
    private static final String STATE_CAT = "list_cat";
    private static final String STATE_KEYWORD = "list_keyword";
    private static final String STATE_LEVEL = "list_level";

    private SearchView searchView;
    private TextView tvStats;
    private TextView tvEmpty;
    private Adapter adapter;

    /** 题库全量（最新添加在前） */
    private final List<Question> allQuestions = new ArrayList<>();
    /** 当前实际展示的集合（筛选 + 搜索之后） */
    private final List<Question> shown = new ArrayList<>();

    private final TextView[] chips = new TextView[CAT_LABELS.length];
    /** 已选中的类别胶囊按位叠加；0 = 全部 */
    private int catMask = 0;

    /** 等级筛选：-1 表示全部等级，否则对应 levelIndex 0..5 */
    private final TextView[] levelChips = new TextView[LEVEL_LABELS.length + 1];
    private int levelFilter = -1;

    /** 搜索关键词的拆分结果（小写），空表示没有搜索 */
    private final List<String> tokens = new ArrayList<>();
    /** 搜索框里的原始文本（用于旋转重建后回填） */
    private String keywordText = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;

    /** 网格列数：宽屏（横屏平板）3 列，窄屏 2 列 */
    private int spanCount = 2;
    /** 网格内图片应加载的目标宽度（px），传给 ImageLoader 网格缩略图 */
    private int colImgPx = 600;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_question_bank);

        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        searchView = findViewById(R.id.search_view);
        tvStats = findViewById(R.id.tv_stats);
        tvEmpty = findViewById(R.id.tv_empty);

        // 网格：宽屏 3 列、窄屏 2 列；卡片高度随图（瀑布流），不再留白条
        float density = getResources().getDisplayMetrics().density;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenDp = (int) (screenW / density);
        spanCount = screenDp >= 1000 ? 3 : 2;
        colImgPx = Math.max(300, screenW / spanCount - dp(16)); // item 左右各 8dp margin
        recyclerView.setLayoutManager(new StaggeredGridLayoutManager(spanCount,
                StaggeredGridLayoutManager.VERTICAL));
        adapter = new Adapter(shown);
        recyclerView.setAdapter(adapter);

        // 进页面时不要自动弹出软键盘（旋转重建时尤其明显）
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);

        // 旋转重建：恢复筛选胶囊与关键词；savedInstanceState 为空才是首次进入
        if (savedInstanceState != null) {
            catMask = savedInstanceState.getInt(STATE_CAT, 0);
            keywordText = savedInstanceState.getString(STATE_KEYWORD, "");
            levelFilter = savedInstanceState.getInt(STATE_LEVEL, -1);
        }

        buildChips(findViewById(R.id.chip_row));
        buildLevelChips(findViewById(R.id.chip_row_level));

        // 标题栏：点击进入题库管理
        findViewById(R.id.tv_bank_title).setOnClickListener(v ->
                startActivity(new Intent(this, SaveManageActivity.class))
        );

        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                scheduleSearch(query, 0);
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                scheduleSearch(newText, SEARCH_DEBOUNCE_MS);
                return false;
            }
        });

        if (!keywordText.isEmpty()) {
            // 回填搜索框；onQueryTextChange 会照正常流程防抖重新过滤
            searchView.setQuery(keywordText, false);
            searchView.clearFocus();
        }

        reload();
    }

    // ==================== 顶部筛选胶囊 ====================

    private void buildChips(LinearLayout row) {
        for (int i = 0; i < CAT_LABELS.length; i++) {
            final int index = i;
            TextView chip = new TextView(this);
            chip.setText(CAT_LABELS[i]);
            chip.setTextSize(13);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), dp(7), dp(14), dp(7));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(8));
            chip.setLayoutParams(lp);

            chip.setOnClickListener(v -> {
                // 0 号「全部」= 清空所有类别条件；其余胶囊各自开关，可同时选中
                if (index == 0) {
                    catMask = 0;
                } else {
                    catMask ^= (1 << index);
                }
                styleChips();
                applyFilter();
            });

            chips[i] = chip;
            row.addView(chip);
        }
        styleChips();
    }

    private void styleChips() {
        for (int i = 0; i < chips.length; i++) {
            TextView chip = chips[i];
            if (chip == null) continue;
            boolean on = (i == 0) ? catMask == 0 : (catMask & (1 << i)) != 0;
            chip.setBackgroundResource(on ? R.drawable.bg_chip_on : R.drawable.bg_chip_off);
            chip.setTextColor(on ? 0xffffffff : 0xff666666);
        }
    }

    private void buildLevelChips(LinearLayout row) {
        // 第 0 个胶囊为「全部」，其后依次对应 S A B C D E
        TextView allChip = new TextView(this);
        allChip.setText("全部");
        allChip.setTextSize(13);
        allChip.setGravity(Gravity.CENTER);
        allChip.setPadding(dp(14), dp(7), dp(14), dp(7));
        LinearLayout.LayoutParams allLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        allLp.setMarginEnd(dp(8));
        allChip.setLayoutParams(allLp);
        allChip.setOnClickListener(v -> selectLevel(-1));
        levelChips[0] = allChip;
        row.addView(allChip);

        for (int i = 0; i < LEVEL_LABELS.length; i++) {
            final int level = i;
            TextView chip = new TextView(this);
            chip.setTextSize(13);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), dp(7), dp(14), dp(7));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(8));
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> selectLevel(level));
            levelChips[i + 1] = chip;
            row.addView(chip);
        }
        styleLevelChips();
    }

    private void selectLevel(int level) {
        if (levelFilter == level) {
            // 再点已选中的等级：取消等级筛选，回到全部等级
            if (levelFilter != -1) {
                levelFilter = -1;
                styleLevelChips();
                applyFilter();
            }
            return;
        }
        levelFilter = level;
        styleLevelChips();
        applyFilter();
    }

    private void styleLevelChips() {
        for (int i = 0; i < levelChips.length; i++) {
            TextView chip = levelChips[i];
            if (chip == null) continue;
            int chipLevel = i - 1; // 0 号胶囊对应全部等级
            boolean on = (chipLevel == levelFilter);
            chip.setBackgroundResource(on ? R.drawable.bg_chip_on : R.drawable.bg_chip_off);
            chip.setTextColor(on ? 0xffffffff : 0xff666666);
        }
    }

    /** 按全量题库刷新各等级胶囊上的数量（与统计条等级分布口径一致） */
    private void refreshLevelCounts() {
        int[] level = new int[LEVEL_LABELS.length];
        for (Question q : allQuestions) {
            int idx = levelIndex(q.status);
            if (idx >= 0) level[idx]++;
        }
        for (int i = 0; i < LEVEL_LABELS.length; i++) {
            TextView chip = levelChips[i + 1];
            if (chip != null) chip.setText(LEVEL_LABELS[i] + " " + level[i]);
        }
    }

    // ==================== 数据加载与筛选 ====================

    private void reload() {
        allQuestions.clear();
        allQuestions.addAll(QuestionStorage.loadAll(this));
        // 倒序：最新添加的在最上面（沿用原有排序，不改语义）
        Collections.reverse(allQuestions);

        refreshLevelCounts();
        refreshTitle();
        applyFilter();
    }

    private void refreshTitle() {
        ((TextView) findViewById(R.id.tv_bank_title))
                .setText("题库：" + QuestionStorage.getCurrentBankName(this) + " ▾");
    }

    private void scheduleSearch(String text, long delay) {
        if (pendingSearch != null) handler.removeCallbacks(pendingSearch);
        final String query = text == null ? "" : text;
        keywordText = query;
        pendingSearch = () -> {
            setTokens(query);
            applyFilter();
        };
        handler.postDelayed(pendingSearch, delay);
    }

    /** 关键词按空白拆成多个词，全部命中才算匹配（AND） */
    private void setTokens(String keyword) {
        tokens.clear();
        if (keyword == null) return;
        String cleaned = keyword.replace('\u3000', ' ').trim();
        if (cleaned.isEmpty()) return;
        for (String part : cleaned.split("\\s+")) {
            String low = part.toLowerCase(Locale.getDefault());
            if (!low.isEmpty()) tokens.add(low);
        }
    }

    private void applyFilter() {
        shown.clear();
        for (Question q : allQuestions) {
            if (passesFilter(q) && passesKeyword(q)) shown.add(q);
        }
        adapter.updateList(shown);
        updateEmptyState();
        updateStats();
    }

    /** 类别条件逐个 AND 叠加；catMask 为 0 时不过滤，等级单选再叠一层 */
    private boolean passesFilter(Question q) {
        if ((catMask & CAT_DUE) != 0 && !QuestionStorage.isDue(this, q)) return false;
        if ((catMask & CAT_EXCLUDED) != 0 && !q.excluded) return false;
        if ((catMask & CAT_NOT_EXCLUDED) != 0 && q.excluded) return false;
        if ((catMask & CAT_FAVORITE) != 0 && !q.favorite) return false;
        if (levelFilter >= 0 && levelIndex(q.status) != levelFilter) return false;
        return true;
    }

    private boolean passesKeyword(Question q) {
        if (tokens.isEmpty()) return true;
        String haystack = (safe(q.content) + "\n" + safe(q.answer) + "\n" + safe(q.id))
                .toLowerCase(Locale.getDefault());
        for (String t : tokens) {
            if (!haystack.contains(t)) return false;
        }
        return true;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private void updateEmptyState() {
        if (shown.isEmpty()) {
            tvEmpty.setText(tokens.isEmpty()
                    ? "这个筛选下没有题目"
                    : "没有匹配的题目\n换个关键词，或点「全部」看看");
            tvEmpty.setVisibility(View.VISIBLE);
        } else {
            tvEmpty.setVisibility(View.GONE);
        }
    }

    /** 统计条：题库规模、待复习、已斩/收藏、等级分布；有筛选时带「显示 M / 共 N」 */
    private void updateStats() {
        int due = 0, excluded = 0, favorite = 0;
        int[] level = new int[6]; // S A B C D E
        for (Question q : allQuestions) {
            if (q.excluded) excluded++;
            if (q.favorite) favorite++;
            if (QuestionStorage.isDue(this, q)) due++;
            int idx = levelIndex(q.status);
            if (idx >= 0) level[idx]++;
        }

        String head = "共 " + allQuestions.size() + " 题 · 待复习 " + due
                + " · 已斩 " + excluded + " · 收藏 " + favorite;
        String levels = " · S" + level[0] + " A" + level[1] + " B" + level[2]
                + " C" + level[3] + " D" + level[4] + " E" + level[5];
        String prefix = (catMask != 0 || levelFilter >= 0 || !tokens.isEmpty())
                ? "显示 " + shown.size() + " / " + allQuestions.size() + " · "
                : "";

        tvStats.setText(prefix + head + levels);
    }

    private static int levelIndex(String status) {
        if (status == null) return -1;
        switch (status) {
            case "S": return 0;
            case "A": return 1;
            case "B": return 2;
            case "C": return 3;
            case "D": return 4;
            case "E": return 5;
            default: return -1;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_CAT, catMask);
        outState.putString(STATE_KEYWORD, keywordText);
        outState.putInt(STATE_LEVEL, levelFilter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 可能刚从详情页改了题，或从题库管理页切了题库
        reload();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    // ==================== 列表 ====================

    public class Adapter extends RecyclerView.Adapter<Adapter.ViewHolder> {

        private List<Question> mList;

        public Adapter(List<Question> list) {
            mList = list;
        }

        public void updateList(List<Question> newList) {
            mList = newList;
            notifyDataSetChanged();
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_grid, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            Question q = mList.get(position);
            int imgCount = q.contentImages == null ? 0 : q.contentImages.size();
            boolean hasText = q.content != null && !q.content.trim().isEmpty();

            holder.bind(q, imgCount, hasText);

            holder.itemView.setOnClickListener(v -> {
                Intent intent = new Intent(v.getContext(), QuestionDetailActivity.class);
                intent.putExtra("qid", q.id);
                v.getContext().startActivity(intent);
            });

            holder.star.setOnClickListener(v -> {
                QuestionStorage.toggleFavorite(v.getContext(), q);
                applyFilter();
            });
        }

        @Override
        public void onViewRecycled(ViewHolder holder) {
            super.onViewRecycled(holder);
            // 清掉 tag，避免还在后台解图的旧任务贴到被复用的行上
            ImageLoader.releaseThumb(holder.thumb);
        }

        @Override
        public int getItemCount() {
            return mList == null ? 0 : mList.size();
        }

        public class ViewHolder extends RecyclerView.ViewHolder {
            final ImageView thumb;
            final TextView text;
            final TextView badge;
            final TextView meta;
            final ImageView star;

            public ViewHolder(View itemView) {
                super(itemView);
                thumb = itemView.findViewById(R.id.thumb);
                text = itemView.findViewById(R.id.text);
                badge = itemView.findViewById(R.id.tv_badge);
                meta = itemView.findViewById(R.id.tv_meta);
                star = itemView.findViewById(R.id.iv_star);
            }

            void bind(Question q, int imgCount, boolean hasText) {
                // 标题：有文本用文本，纯图片题用「图片题 · N 张」
                String title;
                SpannableString titleSpan;
                if (hasText) {
                    title = q.content.trim().replace('\n', ' ');
                    titleSpan = highlight(title);
                } else if (imgCount > 0) {
                    title = "图片题 · " + imgCount + " 张";
                    titleSpan = new SpannableString(title);
                } else {
                    title = "（空题目）";
                    titleSpan = new SpannableString(title);
                }
                text.setText(titleSpan);

                // 首图：宽度撑满卡片、高度随图比例；没有图则整块 GONE
                String firstImage = imgCount > 0 ? q.contentImages.get(0) : null;
                if (firstImage != null) {
                    thumb.setVisibility(View.VISIBLE);
                    ImageLoader.bindGridThumb(itemView.getContext(), thumb, firstImage, colImgPx);
                } else {
                    thumb.setVisibility(View.GONE);
                    ImageLoader.releaseThumb(thumb);
                }

                // 等级徽章：已斩题显示「斩」
                String status = q.status == null ? "E" : q.status;
                if (q.excluded) {
                    badge.setText("斩");
                    badge.setBackgroundTintList(ColorStateList.valueOf(0xff616161));
                } else {
                    badge.setText(status);
                    badge.setBackgroundTintList(ColorStateList.valueOf(statusColor(status)));
                }

                // 副信息：N 张图 · 到期情况 · 上次更新
                StringBuilder sb = new StringBuilder();
                if (imgCount > 0) sb.append(imgCount).append(" 张图");
                if (sb.length() > 0) sb.append(" · ");
                sb.append(QuestionStorage.dueText(itemView.getContext(), q));
                if (!q.excluded) {
                    long days = QuestionStorage.daysSince(itemView.getContext(), q);
                    sb.append(" · ").append(days <= 0 ? "今天更新" : days + " 天前更新");
                }

                meta.setText(sb.toString());

                // 星标：已收藏金色实心，未收藏灰色描边
                star.setImageResource(q.favorite ? R.drawable.ic_star_filled : R.drawable.ic_star_outline);
                star.setContentDescription(q.favorite ? "取消收藏" : "收藏");
            }
        }

        /** 命中词标橙色加粗 */
        private SpannableString highlight(String title) {
            SpannableString span = new SpannableString(title);
            if (tokens.isEmpty()) return span;

            String lower = title.toLowerCase(Locale.getDefault());
            for (String t : tokens) {
                int idx = lower.indexOf(t);
                while (idx >= 0) {
                    span.setSpan(new ForegroundColorSpan(0xfff56c00), idx, idx + t.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    span.setSpan(new StyleSpan(Typeface.BOLD), idx, idx + t.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    idx = lower.indexOf(t, idx + t.length());
                }
            }
            return span;
        }
    }

    /** 等级徽章颜色：口径统一在 QuestionStorage.statusColor */
    private static int statusColor(String status) {
        return QuestionStorage.statusColor(status);
    }
}
