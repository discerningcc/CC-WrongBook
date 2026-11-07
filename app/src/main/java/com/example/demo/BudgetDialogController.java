package com.example.demo;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

/**
 * 每日复习量选择弹窗：设置页与主页共用。
 * 负责快捷档位 / 自定义 / 无限三选一、自定义夹紧，以及弹窗打开态跨旋转重建留存。
 */
final class BudgetDialogController {

    /** 快捷档位；自定义输入会夹紧到 [MIN_CUSTOM, MAX_CUSTOM] */
    static final int[] OPTIONS = {20, 30, 50, 80, 120, 200};
    static final int MIN_CUSTOM = 1;
    static final int MAX_CUSTOM = 500;
    /** budget <= 0 表示不限量 */
    static final int UNLIMITED = 0;

    private static final String KEY_BUDGET_DIALOG = "budget_dialog_open";
    private static final String KEY_CUSTOM_DIALOG = "custom_dialog_open";
    private static final String KEY_CUSTOM_TEXT = "custom_dialog_text";

    private static final int[] CHIP_IDS = {
            R.id.chip_0, R.id.chip_1, R.id.chip_2,
            R.id.chip_3, R.id.chip_4, R.id.chip_5
    };
    private static final int[] CHIP_NUM_IDS = {
            R.id.chip_num_0, R.id.chip_num_1, R.id.chip_num_2,
            R.id.chip_num_3, R.id.chip_num_4, R.id.chip_num_5
    };

    private boolean budgetOpen = false;
    private boolean customOpen = false;
    private EditText customInput;

    /** 打开档位选择弹窗；选定后回调 onChanged 供调用方刷新自身文案/进度 */
    void showBudgetDialog(AppCompatActivity activity, Runnable onChanged) {
        int current = QuestionStorage.getDailyBudget(activity);

        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        View root = LayoutInflater.from(activity).inflate(R.layout.dialog_budget, null);
        dialog.setContentView(root);

        View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet != null) {
            BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(sheet);
            behavior.setPeekHeight(BottomSheetBehavior.PEEK_HEIGHT_AUTO);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }

        TextView currentView = root.findViewById(R.id.tv_budget_current);
        currentView.setText(currentDescription(current));

        for (int i = 0; i < OPTIONS.length; i++) {
            View chip = root.findViewById(CHIP_IDS[i]);
            TextView num = root.findViewById(CHIP_NUM_IDS[i]);
            int value = OPTIONS[i];
            num.setText(String.valueOf(value));
            applyChipState(chip, num, value == current);
            chip.setOnClickListener(v -> {
                QuestionStorage.setDailyBudget(activity, value);
                afterChange(onChanged);
                dialog.dismiss();
            });
        }

        boolean isCustom = current > 0 && !isOption(current);
        View rowCustom = root.findViewById(R.id.row_custom);
        TextView customTitle = root.findViewById(R.id.row_custom_title);
        TextView customSub = root.findViewById(R.id.row_custom_sub);
        applyRowState(rowCustom, customTitle, customSub, isCustom);
        rowCustom.setOnClickListener(v -> {
            dialog.dismiss();
            showCustomInput(activity, null, onChanged);
        });

        View rowUnlimited = root.findViewById(R.id.row_unlimited);
        TextView unlimitedTitle = root.findViewById(R.id.row_unlimited_title);
        TextView unlimitedSub = root.findViewById(R.id.row_unlimited_sub);
        applyRowState(rowUnlimited, unlimitedTitle, unlimitedSub, current <= UNLIMITED);
        rowUnlimited.setOnClickListener(v -> {
            QuestionStorage.setDailyBudget(activity, UNLIMITED);
            afterChange(onChanged);
            dialog.dismiss();
        });

        root.findViewById(R.id.btn_budget_cancel).setOnClickListener(v -> dialog.dismiss());

        dialog.setOnDismissListener(d -> budgetOpen = false);
        budgetOpen = true;
        dialog.show();
    }

    private void showCustomInput(AppCompatActivity activity, String presetText, Runnable onChanged) {
        final EditText input = new EditText(activity);
        customInput = input;
        int current = QuestionStorage.getDailyBudget(activity);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint("输入 " + MIN_CUSTOM + " ~ " + MAX_CUSTOM + " 的数字");
        String preset = presetText != null ? presetText : (current > 0 ? String.valueOf(current) : "");
        input.setText(preset);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("自定义每日复习量")
                .setView(input)
                .setPositiveButton("确定", (d, which) -> {
                    String s = input.getText().toString().trim();
                    int n;
                    try {
                        n = Integer.parseInt(s);
                    } catch (NumberFormatException e) {
                        Toast.makeText(activity, "请输入数字", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (n < MIN_CUSTOM) n = MIN_CUSTOM;
                    if (n > MAX_CUSTOM) n = MAX_CUSTOM;
                    QuestionStorage.setDailyBudget(activity, n);
                    afterChange(onChanged);
                })
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnDismissListener(d -> customOpen = false);
        customOpen = true;
        dialog.show();
    }

    private boolean isOption(int value) {
        for (int option : OPTIONS) {
            if (option == value) return true;
        }
        return false;
    }

    private String currentDescription(int current) {
        if (current <= UNLIMITED) return "当前：不限量";
        return "当前：" + current + " 题/天";
    }

    private void applyChipState(View chip, TextView num, boolean selected) {
        chip.setBackgroundResource(selected
                ? R.drawable.bg_budget_option_selected
                : R.drawable.bg_budget_option_normal);
        num.setTextColor(chip.getContext().getColor(selected
                ? R.color.home_primary
                : R.color.home_text_primary));
        TextView unit = (TextView) ((ViewGroup) chip).getChildAt(1);
        unit.setTextColor(chip.getContext().getColor(selected
                ? R.color.home_primary
                : R.color.home_text_secondary));
    }

    private void applyRowState(View row, TextView title, TextView sub, boolean selected) {
        row.setBackgroundResource(selected
                ? R.drawable.bg_budget_option_selected
                : R.drawable.bg_budget_option_normal);
        title.setTextColor(row.getContext().getColor(selected
                ? R.color.home_primary
                : R.color.home_text_primary));
        sub.setTextColor(row.getContext().getColor(selected
                ? R.color.home_primary
                : R.color.home_text_secondary));
    }

    private void afterChange(Runnable onChanged) {
        if (onChanged != null) onChanged.run();
    }

    void saveInstanceState(Bundle outState) {
        outState.putBoolean(KEY_BUDGET_DIALOG, budgetOpen);
        outState.putBoolean(KEY_CUSTOM_DIALOG, customOpen);
        if (customOpen && customInput != null) {
            outState.putString(KEY_CUSTOM_TEXT, customInput.getText().toString());
        }
    }

    /** 仅系统重建（savedInstanceState 非空）时恢复弹窗；新建流程不重弹 */
    void restore(Bundle savedInstanceState, AppCompatActivity activity, Runnable onChanged) {
        if (savedInstanceState == null) return;
        if (savedInstanceState.getBoolean(KEY_CUSTOM_DIALOG, false)) {
            showCustomInput(activity, savedInstanceState.getString(KEY_CUSTOM_TEXT, null), onChanged);
        } else if (savedInstanceState.getBoolean(KEY_BUDGET_DIALOG, false)) {
            showBudgetDialog(activity, onChanged);
        }
    }
}
