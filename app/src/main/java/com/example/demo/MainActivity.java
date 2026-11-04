package com.example.demo;

import androidx.appcompat.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        refreshTodayCount();
        refreshCurrentBank();

        findViewById(R.id.btn_help).setOnClickListener(v -> {
            String helpContent =
                    "复习节奏（艾宾浩斯）：E(每天)→D(1天)→C(3天)→B(7天)→A(15天)→S(30天抽查)\n" +
                    "记得：升级；模糊：等级不变但下次提前；不会：E~A打回E、S降回A；答错的题本次会再出现一次。\n" +
                    "每日复习量可在设置里调；到期超过预算时，多出来的顺延到后面几天，S 长期题每天抽 1~2 道。\n\n" +
                    "支持多题库管理，题库可导出zip备份（含图片）。\n" +
                    "两种计时模式：自然日 / 24小时间隔。\n\n" +
                    "⚠️ 全部数据本地存储，卸载会丢失，请记得备份！\n" +
                    "联系作者：QQ2916388726";

            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("CC错题本 · 帮助说明")
                    .setMessage(helpContent)
                    .setPositiveButton("知道了", null)
                    .show();
        });

        findViewById(R.id.btn_review).setOnClickListener(v ->
                startActivity(new Intent(this, ReviewActivity.class))
        );
        findViewById(R.id.btn_add).setOnClickListener(v ->
                startActivity(new Intent(this, AddQuestionActivity.class))
        );
        findViewById(R.id.btn_bank).setOnClickListener(v ->
                startActivity(new Intent(this, QuestionBankActivity.class))
        );
        // 【删掉了这一行！】 findViewById(R.id.btn_help).setOnClickListener(v -> {});

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
    }

    private void refreshTodayCount() {
        int queued = QuestionStorage.buildTodayQueue(this).size();
        int dueAll = QuestionStorage.getTodayReview(this).size();
        int budget = QuestionStorage.getDailyBudget(this);
        String text = "今日复习：已排 " + queued + " / 到期 " + dueAll;
        if (dueAll > queued) {
            text += "（预算 " + budget + "，其余顺延）";
        }
        ((TextView) findViewById(R.id.tv_today_review)).setText(text);
    }

    private void refreshCurrentBank() {
        ((TextView) findViewById(R.id.tv_current_bank))
                .setText("当前题库：" + QuestionStorage.getCurrentBankName(this) + " ▾");
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshTodayCount();
        refreshCurrentBank();
    }
}