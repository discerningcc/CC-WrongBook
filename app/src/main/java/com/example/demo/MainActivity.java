package com.example.demo;

import androidx.appcompat.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String KEY_HELP_DIALOG = "help_dialog_open";

    private boolean bankEmpty = false;
    /** 复习量弹窗：与设置页共用同一控制器，变更后回刷今日进度行 */
    private final BudgetDialogController budgetController = new BudgetDialogController();
    /** 帮助弹窗打开态：跨旋转重建留存，关闭后清标记避免误唤醒 */
    private boolean helpOpen = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 孤儿图片扫描放后台线程，避免启动时在主线程遍历全部题库与图片目录
        new Thread(() -> QuestionStorage.sweepOrphanImages(this), "orphan-sweep").start();

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
        int due = 0;
        int fav = 0;
        for (Question q : all) {
            if (QuestionStorage.isDue(this, q)) due++;
            if (q.favorite) fav++;
        }
        int queued = QuestionStorage.buildTodayQueue(this).size();
        int budget = QuestionStorage.getDailyBudget(this);
        int done = QuestionStorage.getTodayDone(this);
        bankEmpty = total == 0;

        ((TextView) findViewById(R.id.tv_current_bank))
                .setText("当前题库：" + QuestionStorage.getCurrentBankName(this) + " ▾");

        TextView main = findViewById(R.id.tv_today_main);
        TextView sub = findViewById(R.id.tv_today_sub);
        ProgressBar progress = findViewById(R.id.progress_today);
        TextView reviewSub = findViewById(R.id.tv_review_sub);
        TextView emptyHint = findViewById(R.id.tv_empty_hint);

        if (budget <= 0) {
            main.setText("今日已完成 " + done + "（不限量）");
            sub.setText("到期 " + due + " 道 · 队列 " + queued);
            progress.setMax(Math.max(1, due));
            progress.setProgress(Math.min(done, due));
        } else if (done >= budget) {
            main.setText("今日目标已完成 " + done + " / " + budget);
            sub.setText("到期还剩 " + due + " 道，可继续多做");
            progress.setMax(budget);
            progress.setProgress(budget);
        } else {
            main.setText("今日已完成 " + done + " / " + budget);
            sub.setText("到期 " + due + " 道 · 队列 " + queued);
            progress.setMax(budget);
            progress.setProgress(done);
        }

        reviewSub.setText("到期 " + due + " 道");
        ((TextView) findViewById(R.id.tv_bank_sub)).setText("收藏 " + fav);
        emptyHint.setVisibility(bankEmpty ? View.VISIBLE : View.GONE);
        findViewById(R.id.btn_review).setAlpha(bankEmpty ? 0.5f : 1f);

        renderCheckin(budget, done, due);
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
            tv.setText("完成目标或无到期题目后可打卡");
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
    }
}
