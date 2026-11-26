package com.example.demo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * EXIF 方向口径单测：标记 → 旋转角 / 镜像的组合关系。
 *
 * 守护点：8 个标记的语义不能串（尤其 4 = 镜像 + 180°、5 = 镜像 + 90°），
 * 一旦有人按「标记值 × 90°」这种想当然的公式改，5/6/7/8 会立刻错位。
 */
public class ExifOrientationTest {

    @Test
    public void normal_needsNothing() {
        assertEquals(0, ExifOrientation.degreesFor(1));
        assertFalse(ExifOrientation.mirroredFor(1));
        assertFalse(ExifOrientation.needsTransform(1));
    }

    @Test
    public void flipHorizontal_mirrorOnly() {
        assertEquals(0, ExifOrientation.degreesFor(2));
        assertTrue(ExifOrientation.mirroredFor(2));
        assertTrue(ExifOrientation.needsTransform(2));
    }

    @Test
    public void rotate180() {
        assertEquals(180, ExifOrientation.degreesFor(3));
        assertFalse(ExifOrientation.mirroredFor(3));
    }

    @Test
    public void flipVertical_mirrorAnd180() {
        assertEquals(180, ExifOrientation.degreesFor(4));
        assertTrue(ExifOrientation.mirroredFor(4));
    }

    @Test
    public void transpose_mirrorAnd90() {
        assertEquals(90, ExifOrientation.degreesFor(5));
        assertTrue(ExifOrientation.mirroredFor(5));
    }

    @Test
    public void rotate90() {
        assertEquals(90, ExifOrientation.degreesFor(6));
        assertFalse(ExifOrientation.mirroredFor(6));
        assertTrue(ExifOrientation.needsTransform(6));
    }

    @Test
    public void transverse_mirrorAnd270() {
        assertEquals(270, ExifOrientation.degreesFor(7));
        assertTrue(ExifOrientation.mirroredFor(7));
    }

    @Test
    public void rotate270() {
        assertEquals(270, ExifOrientation.degreesFor(8));
        assertFalse(ExifOrientation.mirroredFor(8));
    }

    @Test
    public void unknownValue_treatedAsNormal() {
        assertEquals(0, ExifOrientation.degreesFor(0));
        assertEquals(0, ExifOrientation.degreesFor(9));
        assertEquals(0, ExifOrientation.degreesFor(-1));
        assertFalse(ExifOrientation.mirroredFor(0));
        assertFalse(ExifOrientation.needsTransform(9));
    }
}
