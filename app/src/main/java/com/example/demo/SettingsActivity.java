package com.example.demo;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {
    public static final String KEY_NATURAL_DAY = "natural_day_enabled";
    public static final String KEY_TIMER_ENABLED = "timer_enabled";
    /** 打卡总开关（默认开）；关闭自然日模式后该项不可编辑 */
    public static final String KEY_CHECKIN_ENABLED = "checkin_enabled";

    private Switch switchCheckin;
    private TextView tvCheckinHint;
    private TextView tvCheckinTitle;
    private View checkinGuide;
    private TextView budgetRow;
    /** 复习量弹窗：档位/自定义/无限与旋转留存统一由控制器承载 */
    private final BudgetDialogController budgetController = new BudgetDialogController();
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
        budgetRow.setOnClickListener(v ->
                budgetController.showBudgetDialog(this, () -> refreshBudgetRow(budgetRow)));

        budgetController.restore(savedInstanceState, this, () -> refreshBudgetRow(budgetRow));
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

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        budgetController.saveInstanceState(outState);
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
