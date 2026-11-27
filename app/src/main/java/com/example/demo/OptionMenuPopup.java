package com.example.demo;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

/**
 * 贴着锚点展开的选项菜单：白色圆角卡片，选项之间用 1px 细灰线分隔。
 * 列表页「排序」与复习页「右上角菜单」共用，保证两处观感一致。
 */
public final class OptionMenuPopup {

    /** 卡片默认宽度（dp）；必须是固定值，PopupWindow 会把内容视图撑满窗口 */
    public static final int DEFAULT_WIDTH_DP = 184;

    private static final int COLOR_TEXT = 0xff333333;
    private static final int COLOR_TEXT_ON = 0xff1f6aa5;
    private static final int COLOR_TEXT_OFF = 0xffb0b0b0;
    private static final int COLOR_DIVIDER = 0xffececec;

    public interface OnPick {
        /**
         * @param index 被点击项在 labels 中的下标
         */
        void onPick(int index);
    }

    private OptionMenuPopup() {
    }

    /**
     * @param checked 勾选/高亮项下标，-1 表示没有选中项（菜单型）
     * @param enabled 每项是否可用，null 表示全部可用
     */
    public static PopupWindow show(Context ctx, View anchor, String[] labels, int checked,
                                   boolean[] enabled, int widthDp, OnPick onPick) {
        LinearLayout box = (LinearLayout) LayoutInflater.from(ctx)
                .inflate(R.layout.popup_option_menu, null);

        final PopupWindow[] holder = new PopupWindow[1];
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) {
                View line = new View(ctx);
                line.setBackgroundColor(COLOR_DIVIDER);
                box.addView(line, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1));
            }
            box.addView(buildRow(ctx, labels[i], i, checked,
                    enabled == null || enabled[i], holder, onPick));
        }

        final PopupWindow popup = new PopupWindow(box, dp(ctx, widthDp),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(ctx, 6));
        holder[0] = popup;

        // 右边缘对齐锚点，向下留 2dp
        popup.showAsDropDown(anchor, anchor.getWidth() - dp(ctx, widthDp), dp(ctx, 2));
        return popup;
    }

    private static View buildRow(Context ctx, String label, int index, int checked,
                                 boolean enabled, PopupWindow[] holder, OnPick onPick) {
        TextView row = new TextView(ctx);
        row.setText(label);
        row.setTextSize(14);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 18), dp(ctx, 9), dp(ctx, 18), dp(ctx, 9));
        row.setMinHeight(dp(ctx, 42));
        row.setEnabled(enabled);

        boolean on = enabled && index == checked;
        row.setTextColor(!enabled ? COLOR_TEXT_OFF : (on ? COLOR_TEXT_ON : COLOR_TEXT));
        if (on) row.setTypeface(Typeface.DEFAULT_BOLD);

        if (enabled) {
            TypedValue tv = new TypedValue();
            if (ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
                row.setBackgroundResource(tv.resourceId);
            }
            row.setOnClickListener(v -> {
                if (holder[0] != null) holder[0].dismiss();
                onPick.onPick(index);
            });
        }
        return row;
    }

    private static int dp(Context ctx, int value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density);
    }
}
