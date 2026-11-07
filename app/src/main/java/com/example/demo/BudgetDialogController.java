package com.example.demo;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

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

    private boolean budgetOpen = false;
    private boolean customOpen = false;
    private EditText customInput;

    /** 打开档位选择弹窗；选定后回调 onChanged 供调用方刷新自身文案/进度 */
    void showBudgetDialog(AppCompatActivity activity, Runnable onChanged) {
        int current = QuestionStorage.getDailyBudget(activity);

        String[] items = new String[OPTIONS.length + 2];
        for (int i = 0; i < OPTIONS.length; i++) {
            items[i] = OPTIONS[i] + " 题/天";
        }
        int idxCustom = OPTIONS.length;
        int idxUnlimited = OPTIONS.length + 1;
        items[idxCustom] = "自定义（" + MIN_CUSTOM + "~" + MAX_CUSTOM + "）…";
        items[idxUnlimited] = "无限";

        int checked = -1;
        for (int i = 0; i < OPTIONS.length; i++) {
            if (OPTIONS[i] == current) checked = i;
        }
        if (checked < 0) {
            checked = (current <= 0) ? idxUnlimited : idxCustom;
        }

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("每日复习量")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    if (which == idxCustom) {
                        d.dismiss();
                        showCustomInput(activity, null, onChanged);
                    } else if (which == idxUnlimited) {
                        QuestionStorage.setDailyBudget(activity, UNLIMITED);
                        afterChange(onChanged);
                        d.dismiss();
                    } else {
                        QuestionStorage.setDailyBudget(activity, OPTIONS[which]);
                        afterChange(onChanged);
                        d.dismiss();
                    }
                })
                .setNegativeButton("取消", null)
                .create();
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
