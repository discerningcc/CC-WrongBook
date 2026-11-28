package com.example.demo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 主页「未来 7 天到期」柱状图：7 格等宽，第 0 格是今天。
 * 只画柱子、柱顶计数、底部单字星期标签，不画坐标轴和网格；
 * 高度归一用平方根（今天 157 道、明天 2 道这种悬殊分布才看得见后几格），
 * 精确信息由柱顶数字承担。点击某格高亮并回调，再点一次取消。
 */
public class DueChartView extends View {

    public interface OnBucketSelectedListener {
        /** @param index 选中的格子，-1 表示取消选中 */
        void onBucketSelected(int index);
    }

    private static final int COUNT = DueBucketUtil.BUCKET_COUNT;

    private final float density;
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zeroPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint countPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();

    private final int colorPrimary;
    private final int colorBar;
    private final int colorZero;
    private final int colorText;

    private final int[] counts = new int[COUNT];
    private final String[] labels = new String[COUNT];
    private int selected = -1;
    private OnBucketSelectedListener listener;

    public DueChartView(Context context) {
        this(context, null);
    }

    public DueChartView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DueChartView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = getResources().getDisplayMetrics().density;
        colorPrimary = context.getColor(R.color.home_primary);
        colorBar = context.getColor(R.color.home_chart_bar);
        colorZero = context.getColor(R.color.divider);
        colorText = context.getColor(R.color.text_secondary);

        // 字号用 dp，不跟系统字体放大走：柱图高度固定，字被放大就会把柱子挤没
        countPaint.setTextSize(11f * density);
        countPaint.setTextAlign(Paint.Align.CENTER);
        countPaint.setColor(colorText);

        labelPaint.setTextSize(11f * density);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setColor(colorText);

        setClickable(true);
        setFocusable(true);
    }

    public void setData(int[] data) {
        for (int i = 0; i < COUNT; i++) {
            counts[i] = (data != null && i < data.length) ? Math.max(0, data[i]) : 0;
        }
        updateContentDescription();
        invalidate();
    }

    public void setLabels(String[] source) {
        if (source != null) {
            for (int i = 0; i < COUNT && i < source.length; i++) {
                labels[i] = source[i];
            }
        }
        updateContentDescription();
        invalidate();
    }

    public void setSelected(int index) {
        selected = (index >= 0 && index < COUNT) ? index : -1;
        invalidate();
    }

    public int getSelectedIndex() {
        return selected;
    }

    public int countAt(int index) {
        return (index >= 0 && index < COUNT) ? counts[index] : 0;
    }

    public void setOnBucketSelectedListener(OnBucketSelectedListener l) {
        listener = l;
    }

    /** 整块给一句描述，否则读屏只念到一个空 View */
    private void updateContentDescription() {
        StringBuilder sb = new StringBuilder("未来 7 天到期分布：");
        for (int i = 0; i < COUNT; i++) {
            if (i > 0) sb.append('，');
            sb.append(labels[i] == null ? "" : labels[i])
                    .append(' ').append(counts[i]).append(" 道");
        }
        setContentDescription(sb.toString());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        float cell = width / (float) COUNT;
        float labelBaseline = height - 3f * density;          // 底部标签基线
        float chartBottom = height - 19f * density;           // 柱子底线
        float countLift = 18f * density;                      // 顶部数字预留高度
        float maxBar = chartBottom - countLift;                // 最高柱高度
        float countBaseline = chartBottom - maxBar - 4f * density;   // 计数统一一行，不会压到柱顶
        float minBar = 4f * density;
        float barWidth = cell * 0.56f;
        float zeroWidth = cell * 0.22f;

        int max = 0;
        for (int i = 0; i < COUNT; i++) max = Math.max(max, counts[i]);

        for (int i = 0; i < COUNT; i++) {
            float cx = cell * (i + 0.5f);
            float left = cx - barWidth / 2f;
            float right = cx + barWidth / 2f;

            boolean isSel = (selected == i);
            boolean dim = selected >= 0 && !isSel;
            int count = counts[i];

            if (count <= 0) {
                // 0 值不画柱，只留一小截基座短桩（别画满格，否则像一条分割线）
                barRect.set(cx - zeroWidth / 2f, chartBottom - 3f * density,
                        cx + zeroWidth / 2f, chartBottom);
                zeroPaint.setColor(colorZero);
                zeroPaint.setAlpha(dim ? 110 : 255);
                canvas.drawRoundRect(barRect, 1.5f * density, 1.5f * density, zeroPaint);
            } else {
                float frac = DueBucketUtil.heightFraction(count, max);
                float h = minBar + (maxBar - minBar) * frac;
                barRect.set(left, chartBottom - h, right, chartBottom);
                barPaint.setColor((i == 0 || isSel) ? colorPrimary : colorBar);
                barPaint.setAlpha(dim ? 110 : 255);
                canvas.drawRoundRect(barRect, 2f * density, 2f * density, barPaint);
                // 数字贴在各自柱顶上方，矮柱的数字不会飘到天花板上去
                float countY = Math.max(countBaseline, chartBottom - h - 4f * density);
                countPaint.setColor((i == 0 || isSel) ? colorPrimary : colorText);
                countPaint.setAlpha(dim ? 120 : 255);
                canvas.drawText(String.valueOf(count), cx, countY, countPaint);
            }

            String label = labels[i] == null ? "" : labels[i];
            labelPaint.setColor((i == 0 || isSel) ? colorPrimary : colorText);
            labelPaint.setAlpha(dim ? 130 : 255);
            canvas.drawText(label, cx, labelBaseline, labelPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return true;
            case MotionEvent.ACTION_UP: {
                int width = getWidth();
                if (width > 0) {
                    int idx = (int) (event.getX() / (width / (float) COUNT));
                    if (idx >= 0 && idx < COUNT && event.getX() >= 0) {
                        selected = (selected == idx) ? -1 : idx;
                        invalidate();
                        performClick();
                        if (listener != null) listener.onBucketSelected(selected);
                        return true;
                    }
                }
                performClick();
                return true;
            }
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
