package com.example.demo;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 去白边口径单测：阈值、留白、92% 免裁门槛、全白、归一化换算。
 *
 * 这套口径列表缩略图和编辑页共用，改一个数字两处观感一起变，所以边界值要钉死在这里。
 */
public class AutoTrimTest {

    private static final int WHITE = 0xFFFFFFFF;
    private static final int INK = 0xFF000000;

    private static final float EPS = 1e-5f;

    /** 造一张纯白底图，再把给定矩形涂成墨色 */
    private static int[] image(int w, int h, int l, int t, int r, int b) {
        int[] px = new int[w * h];
        java.util.Arrays.fill(px, WHITE);
        if (r < l || b < t) return px;
        for (int y = Math.max(0, t); y <= Math.min(h - 1, b); y++) {
            for (int x = Math.max(0, l); x <= Math.min(w - 1, r); x++) {
                px[y * w + x] = INK;
            }
        }
        return px;
    }

    private static AutoTrim.Result find(int[] px, int w, int h) {
        AutoTrim.Result r = AutoTrim.find(px, w, h);
        assertNotNull(r);
        return r;
    }

    // ==================== 阈值 ====================

    @Test
    public void threshold_238IsBackground_237IsContent() {
        // 238/238/238 三通道都不低于阈值：整张图等于全白
        int[] px = image(50, 50, -1, -1, -2, -2);
        px[25 * 50 + 25] = 0xFFEEEEEE;
        assertFalse(find(px, 50, 50).changed);

        // 237 就算内容，框会围着这一个点收紧
        px[25 * 50 + 25] = 0xFFEDEDED;
        AutoTrim.Result r = find(px, 50, 50);
        assertTrue(r.changed);
        assertEquals(25 - AutoTrim.PAD, r.left);
        assertEquals(25 + AutoTrim.PAD, r.right);
    }

    @Test
    public void singleChannelBelowThresholdCounts() {
        // 只有红通道满，绿蓝都低：算内容
        int[] px = image(50, 50, -1, -1, -2, -2);
        px[10 * 50 + 10] = 0xFFFF0000;
        assertTrue(find(px, 50, 50).changed);
    }

    // ==================== 外接框与留白 ====================

    @Test
    public void box_growsByPadOnEverySide() {
        // 200x100，内容 40..119 / 20..59
        AutoTrim.Result r = find(image(200, 100, 40, 20, 119, 59), 200, 100);
        assertTrue(r.changed);
        assertEquals(40 - AutoTrim.PAD, r.left);
        assertEquals(20 - AutoTrim.PAD, r.top);
        assertEquals(119 + AutoTrim.PAD, r.right);
        assertEquals(59 + AutoTrim.PAD, r.bottom);
        assertEquals(200, r.width);
        assertEquals(100, r.height);
    }

    @Test
    public void box_padIsClampedAtImageEdge() {
        // 内容贴着左上角，留白不能越界成负数
        AutoTrim.Result r = find(image(100, 100, 0, 0, 39, 39), 100, 100);
        assertEquals(0, r.left);
        assertEquals(0, r.top);

        // 贴右下角同理，不能超出 w-1 / h-1
        r = find(image(100, 100, 59, 59, 99, 99), 100, 100);
        assertEquals(99, r.right);
        assertEquals(99, r.bottom);
    }

    @Test
    public void box_singlePixelGetsPadSquare() {
        AutoTrim.Result r = find(image(100, 100, 50, 50, 50, 50), 100, 100);
        assertEquals(50 - AutoTrim.PAD, r.left);
        assertEquals(50 + AutoTrim.PAD, r.bottom);
    }

    // ==================== 免裁门槛 ====================

    @Test
    public void allWhite_neverCrops() {
        AutoTrim.Result r = find(image(120, 90, -1, -1, -2, -2), 120, 90);
        assertFalse(r.changed);
        assertEquals(120, r.width);
        assertEquals(90, r.height);
        assertArrayEquals(new float[]{0f, 0f, 1f, 1f}, r.toCrop(), EPS);
    }

    @Test
    public void contentFilled_neverCrops() {
        AutoTrim.Result r = find(image(60, 60, 0, 0, 59, 59), 60, 60);
        assertFalse(r.changed);
    }

    @Test
    public void contentAtExactly92Percent_isTreatedAsClean() {
        // 100 宽 100 高，内容 100x84，加留白后 100x92 = 恰好 92%，卡在门槛上 → 免裁
        AutoTrim.Result r = find(image(100, 100, 0, 0, 99, 83), 100, 100);
        assertFalse(r.changed);
        assertEquals(0, r.left);
        assertEquals(99, r.right);
        assertEquals(83 + AutoTrim.PAD, r.bottom);
    }

    @Test
    public void contentJustBelow92Percent_stillCrops() {
        // 内容 100x83（0..82），加留白 100x91 = 91% < 92% → 还是要裁
        AutoTrim.Result r = find(image(100, 100, 0, 0, 99, 82), 100, 100);
        assertTrue(r.changed);
        assertEquals(99, r.right);
        assertEquals(82 + AutoTrim.PAD, r.bottom);
    }

    // ==================== 归一化换算 ====================

    @Test
    public void toCrop_mapsBoxToZeroOne() {
        AutoTrim.Result r = find(image(200, 100, 40, 20, 119, 59), 200, 100);
        assertArrayEquals(new float[]{
                32 / 200f, 12 / 100f, 128 / 200f, 68 / 100f}, r.toCrop(), EPS);
    }

    @Test
    public void toCrop_alwaysInsideUnitSquare() {
        // 极端：内容贴满右边，右下为开区间时不能超过 1
        AutoTrim.Result r = find(image(100, 100, 90, 90, 99, 99), 100, 100);
        float[] c = r.toCrop();
        assertTrue(c[2] <= 1f + EPS);
        assertTrue(c[3] <= 1f + EPS);
        assertTrue(c[0] >= -EPS);
        assertTrue(c[1] >= -EPS);
    }

    // ==================== 脏输入 ====================

    @Test
    public void badInput_isSafeNoCrop() {
        assertFalse(AutoTrim.find(null, 10, 10).changed);
        assertFalse(AutoTrim.find(new int[10], 10, 10).changed);   // 长度不足
        assertFalse(AutoTrim.find(new int[4], 0, 0).changed);
    }
}
