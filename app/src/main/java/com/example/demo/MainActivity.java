package com.example.demo;

import androidx.appcompat.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Calendar;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String KEY_HELP_DIALOG = "help_dialog_open";
    /** 柱图用法弹窗打开态：旋转重建后保留 */
    private static final String KEY_CHART_HELP = "chart_help_open";
    /** 柱图选中格：旋转重建后保留 */
    private static final String KEY_CHART_SELECTED = "chart_selected";
    private static final int DAYS_AHEAD = DueBucketUtil.BUCKET_COUNT;

    private boolean bankEmpty = false;
    /** 复习量弹窗：与设置页共用同一控制器，变更后回刷今日进度行 */
    private final BudgetDialogController budgetController = new BudgetDialogController();
    /** 帮助弹窗打开态：跨旋转重建留存，关闭后清标记避免误唤醒 */
    private boolean helpOpen = false;
    /** 柱图用法弹窗打开态：同上 */
    private boolean chartHelpOpen = false;
    /** 未来 7 天柱图选中格；-1 = 未选中 */
    private int chartSelected = -1;
    private DueChartView chart;
    private TextView chartTip;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 启动维护（会话文件名迁移、孤儿图/缩略图清理，后者一天最多一次）全放后台线程，别拖慢首屏
        new Thread(() -> {
            SessionStorage.prepare(this);
            QuestionStorage.sweepImagesIfDue(this);
        }, "startup-maintenance").start();

        chart = findViewById(R.id.due_chart);
        chartTip = findViewById(R.id.tv_chart_tip);
        if (savedInstanceState != null) {
            chartSelected = savedInstanceState.getInt(KEY_CHART_SELECTED, -1);
        }
        chart.setOnBucketSelectedListener(index -> {
            chartSelected = index;
            showChartTip();
        });

        refreshHome();

        // 今日完成行：点击弹出与设置页一致的复习量选择弹窗，变更后回刷进度
        View todayRow = findViewById(R.id.tv_today_main);
        View todaySub = findViewById(R.id.tv_today_sub);
        Runnable openBudget = () ->
                budgetController.showBudgetDialog(this, this::refreshHome);
        todayRow.setOnClickListener(v -> openBudget.run());
        todaySub.setOnClickListener(v -> openBudget.run());

        budgetController.restore(savedInstanceState, this, this::refreshHome);

        findViewById(R.id.btn_help).setOnClickListener(v -> showHelpDialog());

        // 「未来 7 天 · 用法」：点标题看柱子怎么读、新旧题怎么配比
        findViewById(R.id.tv_due_title).setOnClickListener(v -> showChartHelpDialog());

        // 旋转重建后恢复柱图用法弹窗；仅系统重建（savedInstanceState 非空）时执行
        if (savedInstanceState != null && savedInstanceState.getBoolean(KEY_CHART_HELP, false)) {
            showChartHelpDialog();
        }

        // 旋转重建后恢复帮助弹窗；仅系统重建（savedInstanceState 非空）时执行
        if (savedInstanceState != null && savedInstanceState.getBoolean(KEY_HELP_DIALOG, false)) {
            showHelpDialog();
        }

        findViewById(R.id.btn_review).setOnClickListener(v -> {
            if (bankEmpty) {
                Toast.makeText(this, "当前题库还没有题目，先添加新题吧", Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(new Intent(this, ReviewActivity.class));
        });
        findViewById(R.id.btn_add).setOnClickListener(v ->
                startActivity(new Intent(this, AddQuestionActivity.class))
        );
        findViewById(R.id.btn_bank).setOnClickListener(v ->
                startActivity(new Intent(this, QuestionBankActivity.class))
        );

        findViewById(R.id.ic_setting).setOnClickListener(v -> {
            startActivity(new Intent(this, SettingsActivity.class));
        });
        // 顶部当前题库 → 题库管理页
        findViewById(R.id.tv_current_bank).setOnClickListener(v -> {
            startActivity(new Intent(this, SaveManageActivity.class));
        });

        // 复习记录：随时可查看已归档的复习足迹
        findViewById(R.id.btn_history).setOnClickListener(v ->
                startActivity(new Intent(this, ReviewHistoryActivity.class))
        );

        // 备份提示：点一下去题库管理页，那里右上角是导出入口
        findViewById(R.id.tv_backup_hint).setOnClickListener(v ->
                startActivity(new Intent(this, SaveManageActivity.class))
        );

        // 打卡文本：仅在满足资格时响应点击
        findViewById(R.id.tv_checkin).setOnClickListener(v -> onCheckinClick());
        // 日历入口：历史记录随时可查看（24h 模式下只读）
        findViewById(R.id.iv_checkin_calendar).setOnClickListener(v ->
                startActivity(new Intent(this, CheckinCalendarActivity.class))
        );
    }

    private void refreshHome() {
        List<Question> all = QuestionStorage.loadAll(this);
        int total = all.size();
        // 两套口径：文案「待复习 N 道」= 今天该复习的全部（含逾期与 E 级）；柱图每格 = 那一天到期的新题
        int[] buckets = QuestionStorage.dueBuckets(this, DAYS_AHEAD);
        int due = QuestionStorage.countDue(this);
        int budget = QuestionStorage.getDailyBudget(this);
        int done = QuestionStorage.getTodayDone(this);
        bankEmpty = total == 0;

        ((TextView) findViewById(R.id.tv_current_bank))
                .setText("当前题库：" + QuestionStorage.getCurrentBankName(this) + " ▾");

        TextView main = findViewById(R.id.tv_today_main);
        TextView sub = findViewById(R.id.tv_today_sub);
        ProgressBar progress = findViewById(R.id.progress_today);
        TextView emptyHint = findViewById(R.id.tv_empty_hint);

        if (budget <= 0) {
            main.setText("今日已完成 " + done + "（不限量）");
            sub.setText("待复习 " + due + " 道");
            progress.setMax(Math.max(1, due));
            progress.setProgress(Math.min(done, due));
        } else if (done >= budget) {
            main.setText("今日目标已完成 " + done + " / " + budget);
            sub.setText("待复习还剩 " + due + " 道，可继续多做");
            progress.setMax(budget);
            progress.setProgress(budget);
        } else {
            main.setText("今日已完成 " + done + " / " + budget);
            sub.setText("待复习 " + due + " 道");
            progress.setMax(budget);
            progress.setProgress(done);
        }

        emptyHint.setVisibility(bankEmpty ? View.VISIBLE : View.GONE);
        findViewById(R.id.btn_review).setAlpha(bankEmpty ? 0.5f : 1f);

        renderCheckin(budget, done, due);
        renderDueChart(buckets, total);
        renderBackupHint(total);
    }

    // ==================== 未来 7 天柱图 ====================

    /** 7 格标签：第 0 格「今」，其余单字星期，跨月也不会出现怪日期 */
    private String[] chartLabels() {
        String[] labels = new String[DAYS_AHEAD];
        String week = "日一二三四五六";
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(System.currentTimeMillis());
        for (int i = 0; i < DAYS_AHEAD; i++) {
            if (i == 0) {
                labels[0] = "今";
                continue;
            }
            cal.add(Calendar.DAY_OF_MONTH, 1);   // 逐日推进，跨月/跨年由 Calendar 自己处理
            labels[i] = String.valueOf(week.charAt(cal.get(Calendar.DAY_OF_WEEK) - 1));
        }
        return labels;
    }

    private void renderDueChart(int[] buckets, int total) {
        View section = findViewById(R.id.due_section);
        if (!SettingsActivity.isDueChartEnabled(this)) {
            // 设置里关掉：整块收起，选中态一并清掉，别留一个看不见的选中格
            section.setVisibility(View.GONE);
            chartSelected = -1;
            chart.setSelected(-1);
            chartTip.setVisibility(View.GONE);
            return;
        }
        int sum = 0;
        for (int c : buckets) sum += c;
        boolean show = total > 0 && sum > 0;
        section.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) {
            chartSelected = -1;
            chart.setSelected(-1);
            chartTip.setVisibility(View.GONE);
            return;
        }
        chart.setLabels(chartLabels());
        chart.setData(buckets);
        chart.setSelected(chartSelected);
        showChartTip();
    }

    private void showChartTip() {
        if (chartSelected < 0 || chartSelected >= DAYS_AHEAD) {
            chartTip.setVisibility(View.GONE);
            return;
        }
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, chartSelected);
        String date = (cal.get(Calendar.MONTH) + 1) + "/" + cal.get(Calendar.DAY_OF_MONTH);
        String when;
        if (chartSelected == 0) {
            // 今柱只数今天新产生的功课：到期 / 添加 / 未过；剩下全是逾期。
            // 四类相加 = 待复习总数，和主页副标题对得上。
            int[] kinds = QuestionStorage.todayBreakdown(this);
            int total = QuestionStorage.countDue(this);
            int overdue = Math.max(0, total - kinds[TodayBreakdownUtil.DUE_TODAY]
                    - kinds[TodayBreakdownUtil.ADDED_TODAY]
                    - kinds[TodayBreakdownUtil.NOT_PASSED_TODAY]);
            chartTip.setText("今天（" + date + "）：到期 "
                    + kinds[TodayBreakdownUtil.DUE_TODAY]
                    + " · 添加 " + kinds[TodayBreakdownUtil.ADDED_TODAY]
                    + " · 未过 " + kinds[TodayBreakdownUtil.NOT_PASSED_TODAY]
                    + " · 逾期 " + overdue);
            chartTip.setVisibility(View.VISIBLE);
            return;
        }
        if (chartSelected == 1) {
            when = "明天";
        } else {
            when = "周" + "日一二三四五六".charAt(cal.get(Calendar.DAY_OF_WEEK) - 1);
        }
        chartTip.setText(when + "（" + date + "）· " + chart.countAt(chartSelected) + " 道到期");
        chartTip.setVisibility(View.VISIBLE);
    }

    // ==================== 备份提示 ====================

    /** 只在题库非空、且超 30 天未备份（或题库建了 7 天还没备份过）时出现 */
    private void renderBackupHint(int total) {
        TextView hint = findViewById(R.id.tv_backup_hint);
        if (!SettingsActivity.isBackupHintEnabled(this)) {
            hint.setVisibility(View.GONE);
            return;
        }
        long now = System.currentTimeMillis();
        long lastExportAt = QuestionStorage.getLastExportAt(this);
        boolean hasRecord = lastExportAt > 0;
        long daysSinceExport = hasRecord
                ? Math.max(0, QuestionStorage.dayNumberLocal(now) - QuestionStorage.dayNumberLocal(lastExportAt))
                : 0;
        long bankAgeDays = Math.max(0, QuestionStorage.dayNumberLocal(now)
                - QuestionStorage.dayNumberLocal(QuestionStorage.getCurrentBankCreatedAt(this)));

        int state = BackupHintUtil.stateOf(hasRecord, daysSinceExport, bankAgeDays, total);
        if (state == BackupHintUtil.HIDE) {
            hint.setVisibility(View.GONE);
            return;
        }
        hint.setText(BackupHintUtil.textOf(hasRecord, daysSinceExport));
        hint.setTextColor(getColor(state == BackupHintUtil.EMPHASIS
                ? R.color.home_primary : R.color.home_text_secondary));
        hint.setVisibility(View.VISIBLE);
    }

    /** 打卡状态行：门禁关闭时以品牌名占位，仅保留日历历史入口 */
    private void renderCheckin(int budget, int done, int due) {
        View row = findViewById(R.id.checkin_row);
        TextView tv = findViewById(R.id.tv_checkin);
        boolean naturalOn = QuestionStorage.isNaturalDayEnabled(this);
        boolean checkinOn = QuestionStorage.isCheckinEnabled(this);
        row.setVisibility(View.VISIBLE);

        if (!naturalOn || !checkinOn) {
            // 保留 weight 占位槽，日历图标仍靠右端；左侧以品牌名占位避免空白突兀
            tv.setVisibility(View.VISIBLE);
            tv.setText("CC错题本");
            tv.setTextColor(getColor(R.color.home_text_secondary));
            tv.setClickable(false);
            return;
        }
        tv.setVisibility(View.VISIBLE);
        boolean already = CheckinStorage.isCheckedToday(this);
        boolean eligible = CheckinStorage.isEligible(true, true, bankEmpty, budget, done, due, already);
        int streak = CheckinStorage.streakDays(
                CheckinStorage.loadKeySet(this), System.currentTimeMillis());

        if (already) {
            tv.setText("今日已打卡 ✓" + (streak > 0 ? " · 连续 " + streak + " 天" : ""));
            tv.setTextColor(getColor(R.color.detail_success));
            tv.setClickable(false);
        } else if (eligible) {
            tv.setText("今日打卡");
            tv.setTextColor(getColor(R.color.detail_primary));
            tv.setClickable(true);
        } else {
            tv.setText("完成目标或待复习清零后可打卡");
            tv.setTextColor(getColor(R.color.home_text_secondary));
            tv.setClickable(false);
        }
    }

    private void onCheckinClick() {
        int budget = QuestionStorage.getDailyBudget(this);
        int done = QuestionStorage.getTodayDone(this);
        int due = 0;
        for (Question q : QuestionStorage.loadAll(this)) {
            if (QuestionStorage.isDue(this, q)) due++;
        }
        boolean already = CheckinStorage.isCheckedToday(this);
        boolean eligible = CheckinStorage.isEligible(
                QuestionStorage.isNaturalDayEnabled(this),
                QuestionStorage.isCheckinEnabled(this),
                bankEmpty, budget, done, due, already);
        if (!eligible) return;

        CheckinStorage.checkIn(this, done, budget, QuestionStorage.getCurrentBankName(this));
        Toast.makeText(this, "打卡成功，已记录今日", Toast.LENGTH_SHORT).show();
        refreshHome();
    }

    private void showHelpDialog() {
        String helpContent =
                "复习节奏（艾宾浩斯）：E(每天)→D(1天)→C(3天)→B(7天)→A(15天)→S(30天抽查)\n" +
                "记得：升级；模糊：等级不变但下次提前；不会：E~A打回E、S降回A；答错的题本次会再出现一次。\n" +
                "每日复习量可在设置里调；到期超过预算时，多出来的顺延到后面几天，S 长期题每天抽 1~2 道。\n\n" +
                "支持多题库管理，题库可导出zip备份（含图片）。\n" +
                "两种计时模式：自然日 / 24小时间隔。\n\n" +
                "⚠️ 全部数据本地存储，卸载会丢失，请记得备份！\n" +
                "联系作者：QQ2916388726";

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("CC错题本 · 帮助说明")
                .setMessage(helpContent)
                .setPositiveButton("知道了", null)
                .create();
        dialog.setOnDismissListener(d -> helpOpen = false);
        helpOpen = true;
        dialog.show();
    }

    /** 「未来 7 天 · 用法」：只说明柱子是什么，并给一条安排复习计划的建议 */
    private void showChartHelpDialog() {
        String content =
                "表示未来几天预计会到期的题目数量\n" +
                "建议结合具体复习情况安排复习计划";

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("未来 7 天")
                .setMessage(content)
                .setPositiveButton("知道了", null)
                .create();
        dialog.setOnDismissListener(d -> chartHelpOpen = false);
        chartHelpOpen = true;
        dialog.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshHome();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        budgetController.saveInstanceState(outState);
        outState.putBoolean(KEY_HELP_DIALOG, helpOpen);
        outState.putBoolean(KEY_CHART_HELP, chartHelpOpen);
        outState.putInt(KEY_CHART_SELECTED, chartSelected);
    }
}
