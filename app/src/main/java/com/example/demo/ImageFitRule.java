package com.example.demo;

/**
 * 单张题目图片的自适应尺寸规则（纯计算，便于单测）。
 * 只处理"恰好一张"的情形：多图仍按固定 280dp 缩略横排，由调用方决定是否启用。
 */
public final class ImageFitRule {

    /** 高/宽超过该比例视为长图（整页扫描类），改按窄宽、不限高显示 */
    public static final float LONG_RATIO = 1.8f;
    /** 长图宽度占可用宽的比例 */
    public static final float LONG_WIDTH_RATIO = 0.6f;
    /** 常规图片高度上限占可视高的比例 */
    public static final float HEIGHT_CAP_RATIO = 0.7f;

    private ImageFitRule() {
    }

    /**
     * 计算单图显示尺寸。
     *
     * @param availWidth  可用宽（px）
     * @param availHeight 可视高（px）
     * @param imageWidth  图片原始宽（px）
     * @param imageHeight 图片原始高（px）
     * @return {宽, 高}（px）；参数非法时返回 null，调用方回退到缩略尺寸
     */
    public static int[] fitSingle(int availWidth, int availHeight,
                                  int imageWidth, int imageHeight) {
        if (availWidth <= 0 || availHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) {
            return null;
        }
        float ratio = (float) imageHeight / (float) imageWidth;
        float width;
        float height;
        if (ratio > LONG_RATIO) {
            width = availWidth * LONG_WIDTH_RATIO;
            height = width * ratio;
        } else {
            width = availWidth;
            height = width * ratio;
            float cap = availHeight * HEIGHT_CAP_RATIO;
            if (height > cap) {
                height = cap;
                width = height / ratio;
            }
        }
        int w = Math.round(width);
        int h = Math.round(height);
        if (w <= 0 || h <= 0) return null;
        return new int[]{w, h};
    }
}
