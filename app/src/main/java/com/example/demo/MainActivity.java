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
                    "复习等级：E(未掌握)→D(3天)→C(7天)→B(15天)→A(完成)\n" +
                    "记得：升级；模糊：不变；不会：重置回E。\n\n" +
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
        int count = QuestionStorage.getTodayReview(this).size();
        ((TextView) findViewById(R.id.tv_today_review)).setText("今日待复习：" + count + " 题");
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