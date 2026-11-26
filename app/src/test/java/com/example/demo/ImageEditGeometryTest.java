package com.example.demo;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 图片编辑几何口径单测：角度累加、选框随图旋转、夹紧、归一化到像素的换算。
 *
 * 这些是编辑页最容易出错又最不易在真机上发现的地方：
 * 选框转错一个方向，用户就永远裁不准；像素换算少取一格，裁出来的边就缺一列。
 */
public class ImageEditGeometryTest {

    private static final float EPS = 1e-5f;

    private static void assertRect(float[] expected, float[] actual) {
        assertEquals(4, actual.length);
        for (int i = 0; i < 4; i++) {
            assertEquals("index " + i, expected[i], actual[i], EPS);
        }
    }

    // ==================== 角度 ====================

    @Test
    public void rotate_accumulatesAndWraps() {
        assertEquals(90, ImageEditGeometry.rotate(0, 90));
        assertEquals(180, ImageEditGeometry.rotate(90, 90));
        assertEquals(270, ImageEditGeometry.rotate(180, 90));
        assertEquals(0, ImageEditGeometry.rotate(270, 90));
    }

    @Test
    public void rotate_backwardsStaysInZeroToThreeSixty() {
        assertEquals(270, ImageEditGeometry.rotate(0, -90));
        assertEquals(180, ImageEditGeometry.rotate(270, -90));
        assertEquals(0, ImageEditGeometry.normalizeAngle(-360));
        assertEquals(90, ImageEditGeometry.normalizeAngle(-270));
    }

    @Test
    public void rotate_fourStepsReturnsToStart() {
        int angle = 0;
        for (int i = 0; i < 4; i++) angle = ImageEditGeometry.rotate(angle, 90);
        assertEquals(0, angle);
    }

    // ==================== 选框随图旋转 ====================

    @Test
    public void rotateCW_mapsLeftTopToRightSide() {
        // 旧框 x∈[0.1,0.6]、y∈[0.2,0.8]；顺时针转后新 x 来自旧 y、新 y 来自旧 x
        float[] r = {0.1f, 0.2f, 0.6f, 0.8f};
        assertRect(new float[]{0.2f, 0.1f, 0.8f, 0.6f}, ImageEditGeometry.rotateCW(r));
    }

    @Test
    public void rotateCCW_undoesRotateCW() {
        float[] r = {0.1f, 0.25f, 0.55f, 0.9f};
        assertRect(r, ImageEditGeometry.rotateCCW(ImageEditGeometry.rotateCW(r)));
        assertRect(r, ImageEditGeometry.rotateCW(ImageEditGeometry.rotateCCW(r)));
    }

    @Test
    public void rotateCW_fourTimesIsIdentity() {
        float[] r = {0.2f, 0.3f, 0.7f, 0.85f};
        float[] out = r;
        for (int i = 0; i < 4; i++) out = ImageEditGeometry.rotateCW(out);
        assertRect(r, out);
    }

    @Test
    public void rotateCW_fullRectStaysFull() {
        assertTrue(ImageEditGeometry.isFull(ImageEditGeometry.rotateCW(ImageEditGeometry.fullRect())));
        assertTrue(ImageEditGeometry.isFull(ImageEditGeometry.rotateCCW(ImageEditGeometry.fullRect())));
    }

    // ==================== 夹紧 ====================

    @Test
    public void clamp_pullsOverflowingEdgesBackInside() {
        float[] r = {-0.1f, -0.05f, 1.1f, 1.2f};
        assertRect(new float[]{0f, 0f, 1f, 1f}, ImageEditGeometry.clamp(r, 0.1f, true, true));
    }

    @Test
    public void clamp_keepsLeftWhenDraggingRightEdge() {
        // 左边被拖到几乎贴住右边、最小宽度又放不下：保左边不动，整块往左退
        float[] r = {0.9f, 0f, 0.95f, 1f};
        float[] out = ImageEditGeometry.clamp(r, 0.2f, true, true);
        assertEquals(1f, out[2], EPS);
        assertEquals(0.8f, out[0], EPS);
    }

    @Test
    public void clamp_keepsRightWhenDraggingLeftEdge() {
        // 拖左边把框拖窄了：0.15 - 0.2 会到负数，于是左边压到 0、右边让出最小宽度
        float[] r = {0.05f, 0f, 0.15f, 1f};
        float[] out = ImageEditGeometry.clamp(r, 0.2f, false, true);
        assertEquals(0f, out[0], EPS);
        assertEquals(0.2f, out[2], EPS);
        assertEquals(0.2f, out[2] - out[0], EPS);
    }

    @Test
    public void clamp_keepsMinimumSideWhenNearRightEdge() {
        float[] r = {0.85f, 0f, 0.9f, 1f};
        float[] out = ImageEditGeometry.clamp(r, 0.3f, true, true);
        assertEquals(1f, out[2], EPS);
        assertEquals(0.7f, out[0], EPS);
    }

    @Test
    public void clamp_keepsAspectIndependentForBothAxes() {
        float[] r = {0.5f, 0.5f, 0.51f, 0.52f};
        float[] out = ImageEditGeometry.clamp(r, 0.1f, true, true);
        assertTrue(out[2] - out[0] >= 0.1f - EPS);
        assertTrue(out[3] - out[1] >= 0.1f - EPS);
    }

    // ==================== 整体平移 ====================

    @Test
    public void translate_movesWithoutChangingSize() {
        float[] r = {0.1f, 0.1f, 0.5f, 0.4f};
        float[] out = ImageEditGeometry.translateClamped(r, 0.2f, 0.3f);
        assertRect(new float[]{0.3f, 0.4f, 0.7f, 0.7f}, out);
    }

    @Test
    public void translate_stopsAtEdgesAndKeepsSize() {
        float[] r = {0.1f, 0.1f, 0.5f, 0.4f};
        float[] out = ImageEditGeometry.translateClamped(r, -0.5f, -0.5f);
        assertEquals(0f, out[0], EPS);
        assertEquals(0f, out[1], EPS);
        assertEquals(0.4f, out[2] - out[0], EPS);
        assertEquals(0.3f, out[3] - out[1], EPS);

        float[] out2 = ImageEditGeometry.translateClamped(r, 0.8f, 0.9f);
        assertEquals(1f, out2[2], EPS);
        assertEquals(1f, out2[3], EPS);
        assertEquals(0.4f, out2[2] - out2[0], EPS);
        assertEquals(0.3f, out2[3] - out2[1], EPS);
    }

    // ==================== 归一化 → 像素 ====================

    @Test
    public void toPixelRect_fullRectCoversWholeBitmap() {
        assertArrayEquals(new int[]{0, 0, 1200, 1600},
                ImageEditGeometry.toPixelRect(ImageEditGeometry.fullRect(), 1200, 1600));
    }

    @Test
    public void toPixelRect_ceilsRightAndBottomSoNoPixelIsLost() {
        float[] r = {1f / 1200f, 1f / 1600f, 1199f / 1200f, 1599f / 1600f};
        int[] px = ImageEditGeometry.toPixelRect(r, 1200, 1600);
        assertEquals(1, px[0]);
        assertEquals(1, px[1]);
        assertEquals(1199, px[2]);
        assertEquals(1599, px[3]);
    }

    @Test
    public void toPixelRect_alwaysAtLeastOnePixel() {
        float[] r = {0.5f, 0.5f, 0.5f, 0.5f};
        int[] px = ImageEditGeometry.toPixelRect(r, 1200, 1600);
        assertEquals(1, px[2] - px[0]);
        assertEquals(1, px[3] - px[1]);
    }

    @Test
    public void toPixelRect_neverEscapesBitmap() {
        float[] r = {-0.2f, -0.2f, 1.3f, 1.3f};
        int[] px = ImageEditGeometry.toPixelRect(r, 1000, 800);
        assertEquals(0, px[0]);
        assertEquals(0, px[1]);
        assertEquals(1000, px[2]);
        assertEquals(800, px[3]);
    }

    @Test
    public void toPixelRect_invalidBitmapReturnsEmpty() {
        assertArrayEquals(new int[]{0, 0, 0, 0},
                ImageEditGeometry.toPixelRect(ImageEditGeometry.fullRect(), 0, 0));
    }

    // ==================== 其它 ====================

    @Test
    public void isFull_onlyForWholeImage() {
        assertTrue(ImageEditGeometry.isFull(ImageEditGeometry.fullRect()));
        assertFalse(ImageEditGeometry.isFull(new float[]{0f, 0f, 0.999f, 1f}));
        assertTrue(ImageEditGeometry.isFull(new float[]{-0.1f, 0f, 1.05f, 1f}));
    }

    @Test
    public void insetRect_neverShrinksBelowMinimum() {
        float[] r = ImageEditGeometry.insetRect(0.45f);
        assertTrue(r[2] - r[0] >= ImageEditGeometry.MIN_SIDE - EPS);
        assertTrue(r[3] - r[1] >= ImageEditGeometry.MIN_SIDE - EPS);
        assertEquals(0.04f, ImageEditGeometry.insetRect(0.04f)[0], EPS);
    }
}
