package com.example.demo;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.function.Consumer;

/**
 * 全应用统一弹窗：浅色圆角卡片（corner_card 14dp）、18sp 粗体标题、14sp 次级正文、
 * 46dp 按钮。危险操作的主按钮改用白底 + 危险色描边 + 危险色文字，避免和普通确认长一样。
 *
 * 四种形态：info（单按钮「知道了」）、confirm（取消 + 主操作）、input（带输入框）、
 * actions（竖排多动作，最后固定「取消」）。
 */
public final class AppDialog {

    private AppDialog() {
    }

    /** 单按钮信息提示，按钮固定「知道了」 */
    public static Dialog info(Activity activity, String title, String message) {
        return info(activity, title, message, null);
    }

    /** 单按钮信息提示，关闭后回调可为 null */
    public static Dialog info(Activity activity, String title, String message, Runnable onClose) {
        Dialog dialog = build(activity, title, message, null);
        View root = dialog.findViewById(R.id.ll_card_actions);
        LinearLayout actions = (LinearLayout) root;
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(button(activity, STYLE_PRIMARY, "知道了"));
        wire(actions, 0, dialog, onClose);
        dialog.show();
        return dialog;
    }

    /** 双按钮确认：左「取消」右主操作，danger 为 true 时主按钮走危险描边样式 */
    public static Dialog confirm(Activity activity, String title, String message,
                                 String confirmText, boolean danger, Runnable onConfirm) {
        return confirm(activity, title, message, confirmText, danger, onConfirm, null);
    }

    /** 双按钮确认，onCancel 在点「取消」或点外部/返回时触发，可为 null */
    public static Dialog confirm(Activity activity, String title, String message,
                                 String confirmText, boolean danger, Runnable onConfirm, Runnable onCancel) {
        Dialog dialog = build(activity, title, message, null);
        LinearLayout actions = dialog.findViewById(R.id.ll_card_actions);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(button(activity, STYLE_SECONDARY, "取消"));
        actions.addView(button(activity, danger ? STYLE_DANGER : STYLE_PRIMARY, confirmText));
        wireConfirm(actions, dialog, onConfirm, onCancel);
        dialog.show();
        return dialog;
    }

    /** 输入弹窗：confirmText 为确认按钮文案，回调拿到 trim 后的文本 */
    public static Dialog input(Activity activity, String title, String hint, String initial,
                               String confirmText, Consumer<String> onConfirm) {
        Dialog dialog = build(activity, title, null, hint);
        EditText input = dialog.findViewById(R.id.et_card_input);
        input.setVisibility(View.VISIBLE);
        if (initial != null) {
            input.setText(initial);
            input.setSelection(initial.length());
        }
        LinearLayout actions = dialog.findViewById(R.id.ll_card_actions);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(button(activity, STYLE_SECONDARY, "取消"));
        actions.addView(button(activity, STYLE_PRIMARY, confirmText));
        TextView cancel = (TextView) actions.getChildAt(0);
        TextView ok = (TextView) actions.getChildAt(1);
        cancel.setLayoutParams(rowParams(activity));
        LinearLayout.LayoutParams okParams = rowParams(activity);
        okParams.leftMargin = dp(activity, 10);
        ok.setLayoutParams(okParams);
        cancel.setOnClickListener(v -> dialog.dismiss());
        ok.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            dialog.dismiss();
            if (onConfirm != null) onConfirm.accept(text);
        });
        dialog.show();
        if (initial == null) input.requestFocus();
        return dialog;
    }

    /**
     * 竖排多动作弹窗：每个按钮占一行；onCancel 非空时补一个「取消」行，
     * 为空则不补（与原来的两/三按钮弹窗一致）。labels 与 runs 一一对应，文案为 null 的项跳过。
     */
    public static Dialog actions(Activity activity, String title, String message,
                                 String[] labels, Runnable[] runs, Runnable onCancel) {
        return actions(activity, title, message, labels, runs, null, onCancel);
    }

    /**
     * 同上，styles 可逐行指定按钮样式（AppDialog.STYLE_*）；为 null 或长度不足时按默认：
     * 第一行主按钮、其余次级按钮。
     */
    public static Dialog actions(Activity activity, String title, String message,
                                 String[] labels, Runnable[] runs, int[] styles, Runnable onCancel) {
        Dialog dialog = build(activity, title, message, null);
        LinearLayout actions = dialog.findViewById(R.id.ll_card_actions);
        // 只改宽高，保留布局里定义的正文与按钮区间距；直接 new LayoutParams 会把 margin 一起抹掉
        LinearLayout.LayoutParams actionParams =
                (LinearLayout.LayoutParams) actions.getLayoutParams();
        actionParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
        actionParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        actions.setLayoutParams(actionParams);
        actions.setOrientation(LinearLayout.VERTICAL);
        int index = 0;
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] == null) continue;
            TextView btn = button(activity,
                    styles != null && i < styles.length && styles[i] >= 0
                            ? styles[i]
                            : (index == 0 ? STYLE_PRIMARY : STYLE_SECONDARY),
                    labels[i]);
            actions.addView(btn, verticalParams(activity, index));
            index++;
        }
        if (onCancel != null) {
            dialog.setOnCancelListener(d -> onCancel.run());
            TextView cancel = button(activity, STYLE_TERTIARY, "取消");
            actions.addView(cancel, verticalParams(activity, index));
            cancel.setOnClickListener(v -> {
                dialog.dismiss();
                onCancel.run();
            });
        }
        for (int i = 0, slot = 0; i < labels.length; i++) {
            if (labels[i] == null) continue;
            final Runnable run = runs[i];
            wire(actions, slot, dialog, run);
            slot++;
        }
        dialog.show();
        return dialog;
    }

    // ==================== 内部实现 ====================

    /** 竖排动作行样式：0 主按钮、1 次级、2 危险、3 弱化（取消行用） */
    public static final int STYLE_PRIMARY = 0;
    public static final int STYLE_SECONDARY = 1;
    public static final int STYLE_DANGER = 2;
    public static final int STYLE_TERTIARY = 3;

    /** 建卡片：标题必填，正文/输入提示按需 */
    private static Dialog build(Activity activity, String title, String message, String hint) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_card, null);
        ((TextView) view.findViewById(R.id.tv_card_title)).setText(title);
        TextView tvMessage = view.findViewById(R.id.tv_card_message);
        if (message == null || message.isEmpty()) {
            tvMessage.setVisibility(View.GONE);
        } else {
            tvMessage.setText(message);
        }
        EditText input = view.findViewById(R.id.et_card_input);
        if (hint != null) input.setHint(hint);
        dialog.setContentView(view);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            int width = Math.min(screenWidth - dp(activity, 32), dp(activity, 460));
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        }
        return dialog;
    }

    private static TextView button(Activity activity, int style, String text) {
        TextView btn = new TextView(activity);
        btn.setText(text);
        btn.setGravity(android.view.Gravity.CENTER);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                activity.getResources().getDimension(R.dimen.text_size_emphasis));
        btn.setTextColor(ContextCompat.getColor(activity, textColor(activity, style)));
        if (style == STYLE_DANGER) {
            btn.setBackgroundResource(R.drawable.bg_dialog_danger);
        } else {
            btn.setBackgroundResource(R.drawable.bg_prompt_button);
            btn.setBackgroundTintList(ContextCompat.getColorStateList(activity, bgColor(style)));
        }
        btn.setClickable(true);
        btn.setFocusable(true);
        return btn;
    }

    private static int bgColor(int style) {
        switch (style) {
            case STYLE_PRIMARY:
                return R.color.action_primary;
            case STYLE_TERTIARY:
                return R.color.page_bg;
            default:
                return R.color.dialog_secondary_bg;
        }
    }

    private static int textColor(Activity activity, int style) {
        switch (style) {
            case STYLE_PRIMARY:
                return R.color.white;
            case STYLE_DANGER:
                return R.color.state_danger_text;
            case STYLE_TERTIARY:
                return R.color.text_secondary;
            default:
                return R.color.action_primary;
        }
    }

    /** 并排按钮：等宽 46dp，中间 10dp 间距 */
    private static LinearLayout.LayoutParams rowParams(Activity activity) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(activity, 46), 1f);
        return lp;
    }

    /** 竖排按钮：整宽 46dp，非首个按钮上方 10dp（与并排按钮一致） */
    private static LinearLayout.LayoutParams verticalParams(Activity activity, int index) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 46));
        if (index > 0) lp.topMargin = dp(activity, 10);
        return lp;
    }

    private static void wire(LinearLayout actions, int slot, Dialog dialog, Runnable run) {
        View child = actions.getChildAt(slot);
        if (child == null) return;
        if (actions.getOrientation() == LinearLayout.HORIZONTAL) {
            LinearLayout.LayoutParams lp = rowParams((Activity) actions.getContext());
            if (slot > 0) lp.leftMargin = dp((Activity) actions.getContext(), 10);
            child.setLayoutParams(lp);
        }
        child.setOnClickListener(v -> {
            dialog.dismiss();
            if (run != null) run.run();
        });
    }

    /** 双按钮：取消走 onCancel，主操作走 onConfirm */
    private static void wireConfirm(LinearLayout actions, Dialog dialog,
                                    Runnable onConfirm, Runnable onCancel) {
        Activity activity = (Activity) actions.getContext();
        View cancel = actions.getChildAt(0);
        View ok = actions.getChildAt(1);
        LinearLayout.LayoutParams lp1 = rowParams(activity);
        cancel.setLayoutParams(lp1);
        LinearLayout.LayoutParams lp2 = rowParams(activity);
        lp2.leftMargin = dp(activity, 10);
        ok.setLayoutParams(lp2);
        cancel.setOnClickListener(v -> {
            dialog.dismiss();
            if (onCancel != null) onCancel.run();
        });
        ok.setOnClickListener(v -> {
            dialog.dismiss();
            if (onConfirm != null) onConfirm.run();
        });
        dialog.setOnCancelListener(d -> {
            if (onCancel != null) onCancel.run();
        });
    }

    private static int dp(Activity activity, int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                activity.getResources().getDisplayMetrics());
    }
}
