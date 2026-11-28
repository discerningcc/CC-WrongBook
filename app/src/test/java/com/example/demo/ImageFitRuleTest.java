package com.example.demo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 单图自适应尺寸规则单测：常规图按宽铺满并被高度上限压回、长图按窄宽不限高。
 */
public class ImageFitRuleTest {

    @Test
    public void wideImage_fillsWidthWhenUnderHeightCap() {
        // 1000×400 的横图，可用 1000×800，高 400 < 上限 560，直接铺满宽
        int[] size = ImageFitRule.fitSingle(1000, 800, 1000, 400);
        assertNotNull(size);
        assertEquals(1000, size[0]);
        assertEquals(400, size[1]);
    }

    @Test
    public void normalImage_cappedByHeight() {
        // 1000×600，可用 1000×500，上限 350 → 宽度回落为 350/0.6 ≈ 583
        int[] size = ImageFitRule.fitSingle(1000, 500, 1000, 600);
        assertNotNull(size);
        assertEquals(350, size[1]);
        assertEquals(583, size[0]);
        assertTrue(size[0] <= 1000);
    }

    @Test
    public void tallImage_usesNarrowWidthWithoutHeightCap() {
        // 1000×2500，比例 2.5 > 1.8，宽取 60%，高按比例算且不受限
        int[] size = ImageFitRule.fitSingle(1000, 500, 1000, 2500);
        assertNotNull(size);
        assertEquals(600, size[0]);
        assertEquals(1500, size[1]);
    }

    @Test
    public void ratioExactlyBoundary_treatedAsNormal() {
        // 1000×1800 比例正好 1.8，按常规图处理：宽铺满，高受上限
        int[] size = ImageFitRule.fitSingle(1000, 500, 1000, 1800);
        assertNotNull(size);
        assertEquals(350, size[1]);
        assertEquals(194, size[0]);
    }

    @Test
    public void invalidInputs_returnNull() {
        assertNull(ImageFitRule.fitSingle(0, 500, 1000, 600));
        assertNull(ImageFitRule.fitSingle(1000, 0, 1000, 600));
        assertNull(ImageFitRule.fitSingle(1000, 500, 0, 600));
        assertNull(ImageFitRule.fitSingle(1000, 500, 1000, 0));
    }

    @Test
    public void extremeRatio_keepsPositiveSize() {
        int[] size = ImageFitRule.fitSingle(10, 10, 1, 100);
        assertNotNull(size);
        assertTrue(size[0] > 0);
        assertTrue(size[1] > 0);
    }
}
