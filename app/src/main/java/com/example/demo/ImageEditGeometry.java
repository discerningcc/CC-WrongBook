package com.example.demo;

/**
 * 图片编辑的纯几何口径，与视图、位图都无关，便于单测。
 *
 * 选框一律用 0..1 归一化坐标 [left, top, right, bottom]，坐标系是「当前显示方向」的图：
 * 用户每转一次，选框跟着图一起转，所以任何时候这四个数都能直接按当前旋转后的位图尺寸换算。
 * 归一化的好处是跨旋转重建只需存四个小数，重建后按新尺寸重算即可，不必留存位图。
 */
final class ImageEditGeometry {

    /** 最小选框边长（归一化），防止拖成一条线 */
    static final float MIN_SIDE = 0.08f;

    private ImageEditGeometry() {
    }

    /** 规范到 0..359（负数按逆时针折算） */
    static int normalizeAngle(int deg) {
        int a = deg % 360;
        return a < 0 ? a + 360 : a;
    }

    /** 角度累加，step 通常为 ±90；返回 0/90/180/270 */
    static int rotate(int deg, int step) {
        return normalizeAngle(normalizeAngle(deg) + step);
    }

    /** 整图选框 */
    static float[] fullRect() {
        return new float[]{0f, 0f, 1f, 1f};
    }

    /**
     * 进裁剪时的初始选框：四边各内缩一点。
     * 手柄贴着图片边缘时不好抓，内缩几个百分点更顺手；内缩量再大也不允许把选框缩到最小边长以下。
     */
    static float[] insetRect(float inset) {
        float maxInset = 0.5f - MIN_SIDE / 2f;
        float i = Math.max(0f, Math.min(inset, maxInset));
        return new float[]{i, i, 1f - i, 1f - i};
    }

    /**
     * 顺时针转 90° 后选框跟着转：点 (x, y) 落到 (1 - y, x)，
     * 于是新的左边界来自旧的 bottom、新的上边界来自旧的 left，依次类推。
     */
    static float[] rotateCW(float[] r) {
        return new float[]{1f - r[3], r[0], 1f - r[1], r[2]};
    }

    /** 逆时针转 90°：点 (x, y) 落到 (y, 1 - x) */
    static float[] rotateCCW(float[] r) {
        return new float[]{r[1], 1f - r[2], r[3], 1f - r[0]};
    }

    /**
     * 夹回界内并保证宽高不小于 minSide。
     * 尺寸不足时由 keepLeft/keepTop 决定保哪一侧：拖右边时保左边不动（keepLeft=true），
     * 拖左边时反过来保右边，这样被拖的那条边不会突然跳动。
     */
    static float[] clamp(float[] r, float minSide, boolean keepLeft, boolean keepTop) {
        float m = Math.max(0.01f, Math.min(minSide, 1f));
        float l = clamp01(r[0]);
        float t = clamp01(r[1]);
        float right = clamp01(r[2]);
        float b = clamp01(r[3]);

        if (right - l < m) {
            if (keepLeft) {
                right = l + m;
                if (right > 1f) {
                    right = 1f;
                    l = 1f - m;
                }
            } else {
                l = right - m;
                if (l < 0f) {
                    l = 0f;
                    right = m;
                }
            }
        }
        if (b - t < m) {
            if (keepTop) {
                b = t + m;
                if (b > 1f) {
                    b = 1f;
                    t = 1f - m;
                }
            } else {
                t = b - m;
                if (t < 0f) {
                    t = 0f;
                    b = m;
                }
            }
        }
        return new float[]{l, t, right, b};
    }

    /** 整体平移后夹回界内，尺寸不变 */
    static float[] translateClamped(float[] r, float dx, float dy) {
        float w = r[2] - r[0];
        float h = r[3] - r[1];
        float l = r[0] + dx;
        if (w >= 1f) {
            l = 0f;
        } else if (l < 0f) {
            l = 0f;
        } else if (l + w > 1f) {
            l = 1f - w;
        }
        float t = r[1] + dy;
        if (h >= 1f) {
            t = 0f;
        } else if (t < 0f) {
            t = 0f;
        } else if (t + h > 1f) {
            t = 1f - h;
        }
        return new float[]{l, t, l + w, t + h};
    }

    /**
     * 归一化选框 → 位图像素矩形 {left, top, right, bottom}，右/下为开区间，宽高至少 1px。
     * 左/上向下取整、右/下向上取整，保证用户看到的边一条像素都不少。
     */
    static int[] toPixelRect(float[] r, int bitmapW, int bitmapH) {
        if (bitmapW <= 0 || bitmapH <= 0) return new int[]{0, 0, 0, 0};
        int l = (int) Math.floor(r[0] * bitmapW);
        int t = (int) Math.floor(r[1] * bitmapH);
        int right = (int) Math.ceil(r[2] * bitmapW);
        int b = (int) Math.ceil(r[3] * bitmapH);
        l = Math.max(0, Math.min(l, bitmapW - 1));
        t = Math.max(0, Math.min(t, bitmapH - 1));
        right = Math.max(l + 1, Math.min(right, bitmapW));
        b = Math.max(t + 1, Math.min(b, bitmapH));
        return new int[]{l, t, right, b};
    }

    /** 选框是否覆盖整图（是则免一次裁剪拷贝） */
    static boolean isFull(float[] r) {
        return r[0] <= 0f && r[1] <= 0f && r[2] >= 1f && r[3] >= 1f;
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }
}
