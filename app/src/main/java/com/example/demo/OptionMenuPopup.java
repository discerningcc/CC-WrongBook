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

import androidx.core.content.ContextCompat;

/**
 * 贴着锚点展开的选项菜单：白色圆角卡片，选项之间用 1px 细灰线分隔。
 * 列表页「排序」与复习页「右上角菜单」共用，保证两处观感一致。
 */
public final class OptionMenuPopup {

    /** 卡片默认宽度（dp）；必须是固定值，PopupWindow 会把内容视图撑满窗口 */
    public static final int DEFAULT_WIDTH_DP = 184;

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
        return show(ctx, anchor, labels, checked, enabled, widthDp, -1, onPick);
    }

    /**
     * @param dangerIndex 用危险色（红字）展示的下标，-1 表示无；用于菜单末尾的破坏性动作
     */
    public static PopupWindow show(Context ctx, View anchor, String[] labels, int checked,
                                   boolean[] enabled, int widthDp, int dangerIndex, OnPick onPick) {
        final PopupWindow[] holder = new PopupWindow[1];
        LinearLayout box = buildBox(ctx, labels, checked, enabled, dangerIndex, holder, onPick);
        final PopupWindow popup = newPopup(ctx, box, widthDp);
        holder[0] = popup;

        // 右边缘对齐锚点，向下留 2dp
        popup.showAsDropDown(anchor, anchor.getWidth() - dp(ctx, widthDp), dp(ctx, 2));
        return popup;
    }

    /**
     * 卡片长按菜单：贴在卡片下沿（左缘对齐、宽度等于卡片宽度）弹出；
     * 卡片太靠屏幕底部时改为从卡片上方翻出，避免菜单被屏幕裁掉。
     *
     * @param widthDp 菜单宽度（dp），调用方一般传卡片宽度，保证与卡片等宽
     */
    public static PopupWindow showForCard(Context ctx, View anchor, String[] labels,
                                          int widthDp, int dangerIndex, OnPick onPick) {
        final PopupWindow[] holder = new PopupWindow[1];
        LinearLayout box = buildBox(ctx, labels, -1, null, dangerIndex, holder, onPick);
        final PopupWindow popup = newPopup(ctx, box, widthDp);
        holder[0] = popup;

        // 先量一次高度，判断卡片下方放不放得下
        final int widthPx = dp(ctx, widthDp);
        box.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int menuH = box.getMeasuredHeight();

        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int screenH = anchor.getRootView().getHeight();
        int spaceBelow = screenH - (loc[1] + anchor.getHeight());
        if (spaceBelow < menuH + dp(ctx, 4)) {
            // 下方不够：往上翻，菜单底边贴在卡片上沿
            popup.showAsDropDown(anchor, 0, -(anchor.getHeight() + menuH + dp(ctx, 2)));
        } else {
            popup.showAsDropDown(anchor, 0, dp(ctx, 2));
        }
        return popup;
    }

    private static LinearLayout buildBox(Context ctx, String[] labels, int checked,
                                         boolean[] enabled, int dangerIndex,
                                         PopupWindow[] holder, OnPick onPick) {
        LinearLayout box = (LinearLayout) LayoutInflater.from(ctx)
                .inflate(R.layout.popup_option_menu, null);
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) {
                View line = new View(ctx);
                line.setBackgroundColor(ContextCompat.getColor(ctx, R.color.divider));
                box.addView(line, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1));
            }
            box.addView(buildRow(ctx, labels[i], i, checked,
                    enabled == null || enabled[i], i == dangerIndex, holder, onPick));
        }
        return box;
    }

    private static PopupWindow newPopup(Context ctx, View content, int widthDp) {
        PopupWindow popup = new PopupWindow(content, dp(ctx, widthDp),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(ctx, 6));
        return popup;
    }

    private static View buildRow(Context ctx, String label, int index, int checked,
                                 boolean enabled, boolean danger, PopupWindow[] holder, OnPick onPick) {
        TextView row = new TextView(ctx);
        row.setText(label);
        row.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                ctx.getResources().getDimension(R.dimen.text_size_body));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 18), dp(ctx, 9), dp(ctx, 18), dp(ctx, 9));
        row.setMinHeight(dp(ctx, 42));
        row.setEnabled(enabled);

        boolean on = enabled && index == checked;
        int color = !enabled
                ? ContextCompat.getColor(ctx, R.color.text_hint)
                : (danger
                      ? ContextCompat.getColor(ctx, R.color.state_danger_text)
                      : (on ? ContextCompat.getColor(ctx, R.color.detail_primary)
                            : ContextCompat.getColor(ctx, R.color.text_primary)));
        row.setTextColor(color);
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
