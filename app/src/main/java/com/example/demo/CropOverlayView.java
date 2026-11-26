package com.example.demo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 裁剪选框浮层：叠在按 FIT_CENTER 显示的图片之上，只负责画遮罩/边框/手柄并接收拖拽。
 *
 * 选框数值始终是 0..1 的归一化坐标，绘制时才按 {@link #setGeometry(RectF)} 给到的图片显示矩形
 * 换算成视图像素。这样屏幕方向、缩放比例怎么变都不用改选框，跨重建也只需存四个小数。
 */
public class CropOverlayView extends View {

    /** 选框被用户拖动后回调，Activity 据此同步自己的 crop，跨重建才不会退回默认 */
    interface OnCropChangedListener {
        void onCropChanged(float[] crop);
    }

    private static final int MODE_NONE = 0;
    private static final int MODE_MOVE = 1;
    private static final int MODE_LEFT = 2;
    private static final int MODE_RIGHT = 3;
    private static final int MODE_TOP = 4;
    private static final int MODE_BOTTOM = 5;
    private static final int MODE_TOP_LEFT = 6;
    private static final int MODE_TOP_RIGHT = 7;
    private static final int MODE_BOTTOM_LEFT = 8;
    private static final int MODE_BOTTOM_RIGHT = 9;

    private final Paint maskPaint = new Paint();
    private final Paint borderPaint = new Paint();
    private final Paint guidePaint = new Paint();
    private final Paint handlePaint = new Paint();
    /** 边框/手柄/三分线的深色垫层：白线画在白底试卷上会消失，垫一层半透明黑保证任何底色都能看见 */
    private final Paint borderShadePaint = new Paint();
    private final Paint guideShadePaint = new Paint();
    private final Paint handleShadePaint = new Paint();

    /** 图片在视图里的显示矩形（FIT_CENTER 后的位置），由 Activity 按当前旋转角度算好传进来 */
    private final RectF display = new RectF();
    /** 当前选框，归一化 */
    private float[] crop = ImageEditGeometry.fullRect();
    /** 按下瞬间的选框，拖动过程一律从它推算，避免逐帧累积误差 */
    private float[] startCrop = null;

    private boolean editable = false;
    private int mode = MODE_NONE;
    private float downX, downY;
    private OnCropChangedListener cropChangedListener;

    private final float touchTolerance;
    private final float cornerLength;
    private final float cornerThickness;
    private final float borderWidth;

    public CropOverlayView(Context context) {
        this(context, null);
    }

    public CropOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float d = context.getResources().getDisplayMetrics().density;
        touchTolerance = 36f * d;
        cornerLength = 22f * d;
        cornerThickness = 3f * d;
        borderWidth = 1.5f * d;

        maskPaint.setColor(0xCC000000);
        maskPaint.setStyle(Paint.Style.FILL);
        borderPaint.setColor(0xFFFFFFFF);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(borderWidth);
        guidePaint.setColor(0x66FFFFFF);
        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setStrokeWidth(1f * d);
        handlePaint.setColor(0xFFFFFFFF);
        handlePaint.setStyle(Paint.Style.FILL);

        // 深色垫层：边框垫在下面稍粗，白线才有黑边轮廓；三分线同理
        borderShadePaint.setColor(0x99000000);
        borderShadePaint.setStyle(Paint.Style.STROKE);
        borderShadePaint.setStrokeWidth(borderWidth + 2f * d);
        guideShadePaint.setColor(0x66000000);
        guideShadePaint.setStyle(Paint.Style.STROKE);
        guideShadePaint.setStrokeWidth(2f * d);
        handleShadePaint.setColor(0x99000000);
        handleShadePaint.setStyle(Paint.Style.FILL);
    }

    /** 设置图片显示矩形；显示尺寸变化后需要重新调用一次。 */
    void setGeometry(RectF rect) {
        if (rect == null) {
            display.setEmpty();
        } else {
            display.set(rect);
        }
        invalidate();
    }

    /** 设置选框（内部拷贝一份，调用方可以复用数组） */
    void setCrop(float[] rect) {
        if (rect == null) return;
        crop = new float[]{rect[0], rect[1], rect[2], rect[3]};
        invalidate();
    }

    /** 取当前选框的副本 */
    float[] getCrop() {
        return new float[]{crop[0], crop[1], crop[2], crop[3]};
    }

    /** 拖动选框时通知外部，保证 Activity 手里的框始终是用户看到的那一份 */
    void setOnCropChangedListener(OnCropChangedListener listener) {
        cropChangedListener = listener;
    }

    /** 是否允许拖动；关掉后浮层不画也不响应触摸 */
    void setEditable(boolean on) {
        editable = on;
        mode = MODE_NONE;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!editable || display.isEmpty()) return;

        RectF box = toViewRect(crop);

        // 遮罩：把选框外的四块涂暗
        canvas.drawRect(0, 0, getWidth(), box.top, maskPaint);
        canvas.drawRect(0, box.bottom, getWidth(), getHeight(), maskPaint);
        canvas.drawRect(0, box.top, box.left, box.bottom, maskPaint);
        canvas.drawRect(box.right, box.top, getWidth(), box.bottom, maskPaint);

        canvas.save();
        canvas.clipRect(display);
        // 三分线：先垫深色再盖白线，白底黑底都看得见
        float third = 1f / 3f;
        for (int i = 1; i <= 2; i++) {
            float x = box.left + box.width() * third * i;
            canvas.drawLine(x, box.top, x, box.bottom, guideShadePaint);
            canvas.drawLine(x, box.top, x, box.bottom, guidePaint);
            float y = box.top + box.height() * third * i;
            canvas.drawLine(box.left, y, box.right, y, guideShadePaint);
            canvas.drawLine(box.left, y, box.right, y, guidePaint);
        }
        // 边框：先粗一圈深色垫，再画白线
        canvas.drawRect(box, borderShadePaint);
        canvas.drawRect(box, borderPaint);
        // 四角 L 形手柄，画在框内侧：sx/sy 为 1 表示往右/往下伸，-1 表示往左/往上伸
        drawCorner(canvas, box, box.left, box.top, 1, 1);
        drawCorner(canvas, box, box.right, box.top, -1, 1);
        drawCorner(canvas, box, box.left, box.bottom, 1, -1);
        drawCorner(canvas, box, box.right, box.bottom, -1, -1);
        canvas.restore();
    }

    /** 以 (x, y) 为拐点朝 (sx, sy) 两个方向各画一段，形成 L；长度不超过选框自身边长 */
    private void drawCorner(Canvas canvas, RectF box, float x, float y, int sx, int sy) {
        float lenX = Math.min(cornerLength, box.width());
        float lenY = Math.min(cornerLength, box.height());
        float hx = sx > 0 ? x : x - lenX;
        float hy = sy > 0 ? y : y - cornerThickness;
        float vx = sx > 0 ? x : x - cornerThickness;
        float vy = sy > 0 ? y : y - lenY;
        float pad = cornerThickness;
        // 两段都先垫深色再盖白色，拐角处不会互相压盖
        canvas.drawRect(hx - pad, hy - pad, hx + lenX + pad, hy + cornerThickness + pad, handleShadePaint);
        canvas.drawRect(vx - pad, vy - pad, vx + cornerThickness + pad, vy + lenY + pad, handleShadePaint);
        canvas.drawRect(hx, hy, hx + lenX, hy + cornerThickness, handlePaint);
        canvas.drawRect(vx, vy, vx + cornerThickness, vy + lenY, handlePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!editable || display.isEmpty()) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                mode = hitTest(ev.getX(), ev.getY());
                if (mode == MODE_NONE) return false;
                startCrop = getCrop();
                downX = ev.getX();
                downY = ev.getY();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mode == MODE_NONE || startCrop == null) return false;
                applyDrag(ev.getX() - downX, ev.getY() - downY);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (mode == MODE_NONE) return false;
                mode = MODE_NONE;
                startCrop = null;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            }
            default:
                return false;
        }
    }

    /** 命中判定：角 > 边 > 框内 */
    private int hitTest(float x, float y) {
        RectF box = toViewRect(crop);
        float t = touchTolerance;
        boolean nearLeft = Math.abs(x - box.left) <= t;
        boolean nearRight = Math.abs(x - box.right) <= t;
        boolean nearTop = Math.abs(y - box.top) <= t;
        boolean nearBottom = Math.abs(y - box.bottom) <= t;
        boolean inX = x >= box.left - t && x <= box.right + t;
        boolean inY = y >= box.top - t && y <= box.bottom + t;

        if (nearLeft && nearTop) return MODE_TOP_LEFT;
        if (nearRight && nearTop) return MODE_TOP_RIGHT;
        if (nearLeft && nearBottom) return MODE_BOTTOM_LEFT;
        if (nearRight && nearBottom) return MODE_BOTTOM_RIGHT;
        if (nearLeft && inY) return MODE_LEFT;
        if (nearRight && inY) return MODE_RIGHT;
        if (nearTop && inX) return MODE_TOP;
        if (nearBottom && inX) return MODE_BOTTOM;
        if (box.contains(x, y)) return MODE_MOVE;
        return MODE_NONE;
    }

    /** 按模式把位移换算成归一化增量，再从 startCrop 重新推算选框 */
    private void applyDrag(float dxPx, float dyPx) {
        float dw = Math.max(1f, display.width());
        float dh = Math.max(1f, display.height());
        float dx = dxPx / dw;
        float dy = dyPx / dh;
        float[] s = startCrop;
        float m = ImageEditGeometry.MIN_SIDE;

        switch (mode) {
            case MODE_MOVE:
                crop = ImageEditGeometry.translateClamped(s, dx, dy);
                break;
            case MODE_LEFT:
                crop = ImageEditGeometry.clamp(
                        new float[]{s[0] + dx, s[1], s[2], s[3]}, m, false, true);
                break;
            case MODE_RIGHT:
                crop = ImageEditGeometry.clamp(
                        new float[]{s[0], s[1], s[2] + dx, s[3]}, m, true, true);
                break;
            case MODE_TOP:
                crop = ImageEditGeometry.clamp(
                        new float[]{s[0], s[1] + dy, s[2], s[3]}, m, true, false);
                break;
            case MODE_BOTTOM:
                crop = ImageEditGeometry.clamp(
                        new float[]{s[0], s[1], s[2], s[3] + dy}, m, true, true);
                break;
            case MODE_TOP_LEFT:
                crop = ImageEditGeometry.clamp(
                        new float[]{s[0] + dx, s[1] + dy, s[2], s[3]}, m, false, false);
                break;
            case MODE_TOP_RIGHT:
                crop = ImageEditGeometry.clamp(
                        new float[]{s[0], s[1] + dy, s[2] + dx, s[3]}, m, true, false);
                break;
            case MODE_BOTTOM_LEFT:
                crop = ImageEditGeometry.clamp(
                        new float[]{s[0] + dx, s[1], s[2], s[3] + dy}, m, false, true);
                break;
            case MODE_BOTTOM_RIGHT:
                crop = ImageEditGeometry.clamp(
                        new float[]{s[0], s[1], s[2] + dx, s[3] + dy}, m, true, true);
                break;
            default:
                return;
        }
        invalidate();
        if (cropChangedListener != null) cropChangedListener.onCropChanged(getCrop());
    }

    /** 归一化选框 → 视图坐标 */
    private RectF toViewRect(float[] r) {
        return new RectF(
                display.left + r[0] * display.width(),
                display.top + r[1] * display.height(),
                display.left + r[2] * display.width(),
                display.top + r[3] * display.height());
    }
}
