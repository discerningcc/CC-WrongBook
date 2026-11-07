package com.example.demo;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {
    public static final String KEY_NATURAL_DAY = "natural_day_enabled";
    public static final String KEY_TIMER_ENABLED = "timer_enabled";
    /** 打卡总开关（默认开）；关闭自然日模式后该项不可编辑 */
    public static final String KEY_CHECKIN_ENABLED = "checkin_enabled";

    /** 快捷档位；自定义输入会夹紧到 [MIN_CUSTOM, MAX_CUSTOM] */
    private static final int[] BUDGET_OPTIONS = {20, 30, 50, 80, 120, 200};
    private static final int MIN_CUSTOM = 1;
    private static final int MAX_CUSTOM = 500;
    /** budget <= 0 表示不限量 */
    private static final int BUDGET_UNLIMITED = 0;

    private static final String KEY_BUDGET_DIALOG = "budget_dialog_open";
    private static final String KEY_CUSTOM_DIALOG = "custom_dialog_open";
    private static final String KEY_CUSTOM_TEXT = "custom_dialog_text";

    private Switch switchCheckin;
    private TextView tvCheckinHint;
    private TextView tvCheckinTitle;
    private View checkinGuide;
    private TextView budgetRow;
    private EditText customInput;
    private boolean budgetDialogOpen = false;
    private boolean customDialogOpen = false;
    /** 程序化刷新开关时屏蔽监听回写，避免禁用态误落盘 */
    private boolean refreshingCheckin = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Switch switchNaturalDay = findViewById(R.id.switch_natural_day);
        SharedPreferences sp = getSharedPreferences("settings", MODE_PRIVATE);

        boolean enabled = sp.getBoolean(KEY_NATURAL_DAY, true);
        switchNaturalDay.setChecked(enabled);
        applySwitchState(switchNaturalDay, enabled, true);

        switchNaturalDay.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sp.edit().putBoolean(KEY_NATURAL_DAY, isChecked).apply();
            applySwitchState(switchNaturalDay, isChecked, true);
            refreshCheckinRow();
        });

        switchCheckin = findViewById(R.id.switch_checkin);
        tvCheckinHint = findViewById(R.id.tv_checkin_hint);
        tvCheckinTitle = findViewById(R.id.tv_checkin_title);
        checkinGuide = findViewById(R.id.view_checkin_guide);
        refreshCheckinRow();
        switchCheckin.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (refreshingCheckin || !switchCheckin.isEnabled()) return;
            QuestionStorage.setCheckinEnabled(this, isChecked);
            applySwitchState(switchCheckin, isChecked, true);
        });

        Switch switchTimer = findViewById(R.id.switch_timer);
        boolean timerEnabled = sp.getBoolean(KEY_TIMER_ENABLED, true);
        switchTimer.setChecked(timerEnabled);
        applySwitchState(switchTimer, timerEnabled, true);
        switchTimer.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sp.edit().putBoolean(KEY_TIMER_ENABLED, isChecked).apply();
            applySwitchState(switchTimer, isChecked, true);
        });

        // 每日复习量：点一下弹「快捷档 + 自定义 + 无限」
        budgetRow = findViewById(R.id.tv_budget_row);
        refreshBudgetRow(budgetRow);
        budgetRow.setOnClickListener(v -> showBudgetDialog());

        restoreTransientState(savedInstanceState);
    }

    /** 旋转重建后恢复复习量弹窗；仅系统重建（savedInstanceState 非空）时执行 */
    private void restoreTransientState(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;
        if (savedInstanceState.getBoolean(KEY_CUSTOM_DIALOG, false)) {
            showCustomInput(savedInstanceState.getString(KEY_CUSTOM_TEXT, null));
        } else if (savedInstanceState.getBoolean(KEY_BUDGET_DIALOG, false)) {
            showBudgetDialog();
        }
    }

    /** 打卡行可用性跟随自然日模式：自然日关 → 灰置禁用，不回写持久值 */
    private void refreshCheckinRow() {
        SharedPreferences sp = getSharedPreferences("settings", MODE_PRIVATE);
        boolean naturalOn = sp.getBoolean(KEY_NATURAL_DAY, true);
        boolean checked = sp.getBoolean(KEY_CHECKIN_ENABLED, true);

        refreshingCheckin = true;
        switchCheckin.setEnabled(naturalOn);
        switchCheckin.setChecked(checked);
        refreshingCheckin = false;

        applySwitchState(switchCheckin, checked, naturalOn);
        if (naturalOn) {
            tvCheckinTitle.setTextColor(getColor(R.color.home_text_primary));
            tvCheckinHint.setText("完成今日目标后可在主页打卡，历史记录可在日历中查看");
            tvCheckinHint.setTextColor(getColor(R.color.home_text_secondary));
            checkinGuide.setBackgroundColor(getColor(R.color.home_stroke));
        } else {
            tvCheckinTitle.setTextColor(getColor(R.color.home_text_secondary));
            tvCheckinHint.setText("24 小时模式无固定日界，打卡功能不可用；历史记录仍保留");
            tvCheckinHint.setTextColor(getColor(R.color.detail_warn));
            checkinGuide.setBackgroundColor(getColor(R.color.home_stroke));
        }
    }

    private void refreshBudgetRow(TextView row) {
        int budget = QuestionStorage.getDailyBudget(this);
        if (budget <= 0) {
            row.setText("每日复习量：不限量（到期多少做多少）（点此修改）");
        } else {
            row.setText("每日复习量：" + budget + " 题/天（点此修改）");
        }
    }

    private void showBudgetDialog() {
        int current = QuestionStorage.getDailyBudget(this);

        String[] items = new String[BUDGET_OPTIONS.length + 2];
        for (int i = 0; i < BUDGET_OPTIONS.length; i++) {
            items[i] = BUDGET_OPTIONS[i] + " 题/天";
        }
        int idxCustom = BUDGET_OPTIONS.length;
        int idxUnlimited = BUDGET_OPTIONS.length + 1;
        items[idxCustom] = "自定义（" + MIN_CUSTOM + "~" + MAX_CUSTOM + "）…";
        items[idxUnlimited] = "无限";

        int checked = -1;
        for (int i = 0; i < BUDGET_OPTIONS.length; i++) {
            if (BUDGET_OPTIONS[i] == current) checked = i;
        }
        if (checked < 0) {
            checked = (current <= 0) ? idxUnlimited : idxCustom;
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("每日复习量")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    if (which == idxCustom) {
                        d.dismiss();
                        showCustomInput(null);
                    } else if (which == idxUnlimited) {
                        QuestionStorage.setDailyBudget(this, BUDGET_UNLIMITED);
                        refreshBudgetRow(budgetRow);
                        d.dismiss();
                    } else {
                        QuestionStorage.setDailyBudget(this, BUDGET_OPTIONS[which]);
                        refreshBudgetRow(budgetRow);
                        d.dismiss();
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnDismissListener(d -> budgetDialogOpen = false);
        budgetDialogOpen = true;
        dialog.show();
    }

    private void showCustomInput(String presetText) {
        final EditText input = new EditText(this);
        customInput = input;
        int current = QuestionStorage.getDailyBudget(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint("输入 " + MIN_CUSTOM + " ~ " + MAX_CUSTOM + " 的数字");
        String preset = presetText != null ? presetText : (current > 0 ? String.valueOf(current) : "");
        input.setText(preset);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("自定义每日复习量")
                .setView(input)
                .setPositiveButton("确定", (d, which) -> {
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
                    refreshBudgetRow(budgetRow);
                })
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnDismissListener(d -> customDialogOpen = false);
        customDialogOpen = true;
        dialog.show();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(KEY_BUDGET_DIALOG, budgetDialogOpen);
        outState.putBoolean(KEY_CUSTOM_DIALOG, customDialogOpen);
        if (customDialogOpen && customInput != null) {
            outState.putString(KEY_CUSTOM_TEXT, customInput.getText().toString());
        }
    }

    private void applySwitchState(Switch switchView, boolean isChecked, boolean enabled) {
        if (!enabled) {
            switchView.setThumbTintList(getColorStateList(R.color.switch_thumb_disabled));
            switchView.setTrackTintList(getColorStateList(R.color.switch_track_disabled));
            return;
        }
        if (isChecked) {
            switchView.setThumbTintList(getColorStateList(R.color.switch_thumb));
            switchView.setTrackTintList(getColorStateList(R.color.switch_track));
        } else {
            switchView.setThumbTintList(getColorStateList(R.color.switch_thumb_disabled));
            switchView.setTrackTintList(getColorStateList(R.color.switch_track_disabled));
        }
    }
}
