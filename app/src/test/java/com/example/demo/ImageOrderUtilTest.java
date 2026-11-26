package com.example.demo;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 图片顺序口径单测：插入式移动（不是互换）。
 *
 * 守护点：一次「末位拖到首位」在真机上会被拆成多次相邻回调，
 * 每次回调都要保持「取出—插入—顺移」的语义，累加后必须仍然正确；
 * 若有人把它改回 Collections.swap，最后两个用例会立刻失败。
 */
public class ImageOrderUtilTest {

    private static List<String> listOf(String... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    @Test
    public void moveBackward_insertsAndShiftsOthersRight() {
        List<String> list = listOf("1", "2", "3", "4", "5", "6", "7", "8");
        assertTrue(ImageOrderUtil.move(list, 7, 0));
        assertEquals(listOf("8", "1", "2", "3", "4", "5", "6", "7"), list);
    }

    @Test
    public void moveForward_insertsAndShiftsOthersLeft() {
        List<String> list = listOf("1", "2", "3", "4", "5", "6", "7", "8");
        assertTrue(ImageOrderUtil.move(list, 0, 7));
        assertEquals(listOf("2", "3", "4", "5", "6", "7", "8", "1"), list);
    }

    @Test
    public void moveAdjacent_swapsNeighboursOnly() {
        List<String> list = listOf("1", "2", "3");
        assertTrue(ImageOrderUtil.move(list, 0, 1));
        assertEquals(listOf("2", "1", "3"), list);
    }

    @Test
    public void moveAcrossGridRows_keepsMiddleItemsInOrder() {
        List<String> list = listOf("a", "b", "c", "d", "e", "f", "g", "h");
        assertTrue(ImageOrderUtil.move(list, 1, 5));
        assertEquals(listOf("a", "c", "d", "e", "f", "b", "g", "h"), list);
    }

    @Test
    public void moveSameIndexOrInvalid_noChangeAndFalse() {
        List<String> list = listOf("1", "2", "3");
        assertFalse(ImageOrderUtil.move(list, 1, 1));
        assertEquals(listOf("1", "2", "3"), list);

        assertFalse(ImageOrderUtil.move(list, -1, 2));
        assertFalse(ImageOrderUtil.move(list, 0, 3));
        assertFalse(ImageOrderUtil.move(list, 3, 0));
        assertEquals(listOf("1", "2", "3"), list);

        assertFalse(ImageOrderUtil.move(new ArrayList<>(), 0, 0));
        assertFalse(ImageOrderUtil.move(null, 0, 1));
    }

    /**
     * 真实回调序列：把末位拖到首位时，ItemTouchHelper 会连续回调 7→6、6→1、1→0。
     * 三次插入式移动累加后必须等于「整体前移一位」，这是原 swap 实现出错的地方。
     */
    @Test
    public void sequentialAdjacentMoves_matchDroppingLastToFirst() {
        List<String> list = listOf("1", "2", "3", "4", "5", "6", "7", "8");
        ImageOrderUtil.move(list, 7, 6);
        ImageOrderUtil.move(list, 6, 1);
        ImageOrderUtil.move(list, 1, 0);
        assertEquals(listOf("8", "1", "2", "3", "4", "5", "6", "7"), list);
    }

    /** 插入式移动不改变元素集合与长度，只改变顺序。 */
    @Test
    public void moveKeepsSizeAndElements() {
        List<String> list = listOf("1", "2", "3", "4", "5");
        ImageOrderUtil.move(list, 4, 0);
        assertEquals(5, list.size());
        List<String> sorted = new ArrayList<>(list);
        Collections.sort(sorted);
        assertEquals(listOf("1", "2", "3", "4", "5"), sorted);
    }
}
