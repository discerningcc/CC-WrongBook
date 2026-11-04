package com.example.demo;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Switch;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {
    public static final String KEY_NATURAL_DAY = "natural_day_enabled";

    /** 可选的每日复习量档位 */
    private static final int[] BUDGET_OPTIONS = {20, 30, 50, 80};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Switch switchNaturalDay = findViewById(R.id.switch_natural_day);
        SharedPreferences sp = getSharedPreferences("settings", MODE_PRIVATE);

        boolean enabled = sp.getBoolean(KEY_NATURAL_DAY, true);
        switchNaturalDay.setChecked(enabled);
        updateSwitchColor(switchNaturalDay, enabled);

        // 这里修复了 👇
        switchNaturalDay.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sp.edit().putBoolean(KEY_NATURAL_DAY, isChecked).apply();
            updateSwitchColor(switchNaturalDay, isChecked);
        });

        // 每日复习量：点一下弹四档选择
        TextView budgetRow = findViewById(R.id.tv_budget_row);
        refreshBudgetRow(budgetRow);
        budgetRow.setOnClickListener(v -> showBudgetDialog(budgetRow));
    }

    private void refreshBudgetRow(TextView row) {
        row.setText("每日复习量：" + QuestionStorage.getDailyBudget(this) + " 题/天（点此修改）");
    }

    private void showBudgetDialog(TextView row) {
        int current = QuestionStorage.getDailyBudget(this);
        String[] items = new String[BUDGET_OPTIONS.length];
        int checked = 0;
        for (int i = 0; i < BUDGET_OPTIONS.length; i++) {
            items[i] = BUDGET_OPTIONS[i] + " 题/天";
            if (BUDGET_OPTIONS[i] == current) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("每日复习量")
                .setSingleChoiceItems(items, checked, (dialog, which) -> {
                    QuestionStorage.setDailyBudget(this, BUDGET_OPTIONS[which]);
                    refreshBudgetRow(row);
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateSwitchColor(Switch switchView, boolean isChecked) {
        if (isChecked) {
            switchView.setThumbTintList(getColorStateList(R.color.switch_thumb));
            switchView.setTrackTintList(getColorStateList(R.color.switch_track));
        } else {
            switchView.setThumbTintList(getColorStateList(R.color.switch_thumb_disabled));
            switchView.setTrackTintList(getColorStateList(R.color.switch_track_disabled));
        }
    }
}