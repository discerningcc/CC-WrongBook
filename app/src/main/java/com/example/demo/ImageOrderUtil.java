package com.example.demo;

import java.util.List;

/**
 * 图片顺序的纯函数口径：插入式移动。
 *
 * 语义与 RecyclerView 的 notifyItemMoved 一致：把 from 处的元素取出、插入到 to 位置，
 * 中间元素整体顺移一位，列表长度不变。
 *
 * 注意不要用 Collections.swap 实现——swap 只互换两项，是「交换」而非「移动」，
 * 跨格拖动时会把被换下去的那一项甩到很远，表现为「画面顺序对、落库顺序错」。
 * 该口径由 ImageOrderUtilTest 守护。
 */
public final class ImageOrderUtil {

    private ImageOrderUtil() {
    }

    /**
     * 就地执行一次插入式移动。
     *
     * @return 数据是否真的发生变化（位置无效或原地不动返回 false）
     */
    public static <T> boolean move(List<T> list, int from, int to) {
        if (list == null) return false;
        int size = list.size();
        if (from < 0 || to < 0 || from >= size || to >= size || from == to) return false;
        list.add(to, list.remove(from));
        return true;
    }
}
