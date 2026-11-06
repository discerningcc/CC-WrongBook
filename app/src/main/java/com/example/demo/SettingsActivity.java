package com.example.demo;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {
    public static final String KEY_NATURAL_DAY = "natural_day_enabled";
    public static final String KEY_TIMER_ENABLED = "timer_enabled";

    /** 快捷档位；自定义输入会夹紧到 [MIN_CUSTOM, MAX_CUSTOM] */
    private static final int[] BUDGET_OPTIONS = {20, 30, 50, 80, 120, 200};
    private static final int MIN_CUSTOM = 1;
    private static final int MAX_CUSTOM = 500;
    /** budget <= 0 表示不限量 */
    private static final int BUDGET_UNLIMITED = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Switch switchNaturalDay = findViewById(R.id.switch_natural_day);
        SharedPreferences sp = getSharedPreferences("settings", MODE_PRIVATE);

        boolean enabled = sp.getBoolean(KEY_NATURAL_DAY, true);
        switchNaturalDay.setChecked(enabled);
        updateSwitchColor(switchNaturalDay, enabled);

        switchNaturalDay.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sp.edit().putBoolean(KEY_NATURAL_DAY, isChecked).apply();
            updateSwitchColor(switchNaturalDay, isChecked);
        });

        Switch switchTimer = findViewById(R.id.switch_timer);
        boolean timerEnabled = sp.getBoolean(KEY_TIMER_ENABLED, true);
        switchTimer.setChecked(timerEnabled);
        updateSwitchColor(switchTimer, timerEnabled);
        switchTimer.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sp.edit().putBoolean(KEY_TIMER_ENABLED, isChecked).apply();
            updateSwitchColor(switchTimer, isChecked);
        });

        // 每日复习量：点一下弹「快捷档 + 自定义 + 不限量」
        TextView budgetRow = findViewById(R.id.tv_budget_row);
        refreshBudgetRow(budgetRow);
        budgetRow.setOnClickListener(v -> showBudgetDialog(budgetRow));
    }

    private void refreshBudgetRow(TextView row) {
        int budget = QuestionStorage.getDailyBudget(this);
        if (budget <= 0) {
            row.setText("每日复习量：不限量（到期多少做多少）（点此修改）");
        } else {
            row.setText("每日复习量：" + budget + " 题/天（点此修改）");
        }
    }

    private void showBudgetDialog(TextView row) {
        int current = QuestionStorage.getDailyBudget(this);

        String[] items = new String[BUDGET_OPTIONS.length + 2];
        for (int i = 0; i < BUDGET_OPTIONS.length; i++) {
            items[i] = BUDGET_OPTIONS[i] + " 题/天";
        }
        int idxCustom = BUDGET_OPTIONS.length;
        int idxUnlimited = BUDGET_OPTIONS.length + 1;
        items[idxCustom] = "自定义（" + MIN_CUSTOM + "~" + MAX_CUSTOM + "）…";
        items[idxUnlimited] = "不限量（全部到期）";

        int checked = -1;
        for (int i = 0; i < BUDGET_OPTIONS.length; i++) {
            if (BUDGET_OPTIONS[i] == current) checked = i;
        }
        if (checked < 0) {
            checked = (current <= 0) ? idxUnlimited : idxCustom;
        }

        new AlertDialog.Builder(this)
                .setTitle("每日复习量")
                .setSingleChoiceItems(items, checked, (dialog, which) -> {
                    if (which == idxCustom) {
                        dialog.dismiss();
                        showCustomInput(row, current);
                    } else if (which == idxUnlimited) {
                        QuestionStorage.setDailyBudget(this, BUDGET_UNLIMITED);
                        refreshBudgetRow(row);
                        dialog.dismiss();
                    } else {
                        QuestionStorage.setDailyBudget(this, BUDGET_OPTIONS[which]);
                        refreshBudgetRow(row);
                        dialog.dismiss();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showCustomInput(TextView row, int current) {
        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint("输入 " + MIN_CUSTOM + " ~ " + MAX_CUSTOM + " 的数字");
        input.setText(current > 0 ? String.valueOf(current) : "");

        new AlertDialog.Builder(this)
                .setTitle("自定义每日复习量")
                .setView(input)
                .setPositiveButton("确定", (dialog, which) -> {
                    String s = input.getText().toString().trim();
                    int n;
                    try {
                        n = Integer.parseInt(s);
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "请输入数字", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (n < MIN_CUSTOM) n = MIN_CUSTOM;
                    if (n > MAX_CUSTOM) n = MAX_CUSTOM;
                    QuestionStorage.setDailyBudget(this, n);
                    refreshBudgetRow(row);
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
