package com.example.demo;

/**
 * 自动去白边：在缩小的分析图上找"非白内容"的外接框。
 *
 * 口径与列表网格缩略图一致（{@link ImageLoader} 的 trimWhite 也走这里）：
 * 1. 图先缩到 {@link #ANALYSIS_WIDTH} 宽再逐像素扫，200 宽已经够看清内容边界，又快。
 * 2. 任一通道低于 {@link #THRESHOLD}（238）就算内容，浅灰字、阴影都能兜住。
 * 3. 框四周留 {@link #PAD} 个分析像素，回原图约 10~20px，避免贴字裁。
 * 4. 内容框已占 {@link #MAX_CONTENT_RATIO}（92%）以上，说明白的部分不足 8%，裁了也没收益，按"不用裁"处理。
 *
 * 纯逻辑，不碰 Bitmap，便于单测；两个调用方各自把结果换算成自己的坐标。
 */
public final class AutoTrim {

    /** 分析图目标宽度 */
    public static final int ANALYSIS_WIDTH = 200;
    /** 任一通道低于它就算内容 */
    public static final int THRESHOLD = 238;
    /** 分析图坐标上的留白 */
    public static final int PAD = 8;
    /** 内容框占比达到它就认为没有白边可裁 */
    public static final float MAX_CONTENT_RATIO = 0.92f;

    private AutoTrim() {
    }

    /**
     * 找内容外接框。
     *
     * @param px ARGB 像素数组，长度不小于 w*h
     * @param w  分析图宽
     * @param h  分析图高
     * @return 结果永不为 null；{@link Result#changed} 为 false 表示不用裁（全白或本来就没多少白边）
     */
    public static Result find(int[] px, int w, int h) {
        if (px == null || w <= 0 || h <= 0 || px.length < w * h) {
            return new Result(false, 0, 0, -1, -1, Math.max(w, 0), Math.max(h, 0));
        }

        int top = h, bottom = -1, left = w, right = -1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int p = px[row + x];
                int r = (p >> 16) & 0xff;
                int g = (p >> 8) & 0xff;
                int b = p & 0xff;
                if (r < THRESHOLD || g < THRESHOLD || b < THRESHOLD) {
                    if (y < top) top = y;
                    if (y > bottom) bottom = y;
                    if (x < left) left = x;
                    if (x > right) right = x;
                }
            }
        }

        // 整图都是白的：没有任何内容，谈不上裁
        if (bottom < 0) return new Result(false, 0, 0, -1, -1, w, h);

        left = Math.max(0, left - PAD);
        top = Math.max(0, top - PAD);
        right = Math.min(w - 1, right + PAD);
        bottom = Math.min(h - 1, bottom + PAD);

        float box = (float) ((right - left + 1) * (bottom - top + 1)) / (w * h);
        if (box >= MAX_CONTENT_RATIO) return new Result(false, left, top, right, bottom, w, h);

        return new Result(true, left, top, right, bottom, w, h);
    }

    /** 分析结果：外接框用分析图坐标，右下角含（left..right、top..bottom 闭区间） */
    public static final class Result {
        /** 是否需要裁 */
        public final boolean changed;
        public final int left, top, right, bottom;
        /** 分析图尺寸，换算原图坐标要用 */
        public final int width, height;

        Result(boolean changed, int left, int top, int right, int bottom, int width, int height) {
            this.changed = changed;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.width = width;
            this.height = height;
        }

        /** 归一化选框 {l,t,r,b}，给编辑页的裁剪浮层用；不用裁时给整图 */
        public float[] toCrop() {
            if (!changed || width <= 0 || height <= 0) {
                return new float[]{0f, 0f, 1f, 1f};
            }
            return new float[]{
                    left / (float) width,
                    top / (float) height,
                    (right + 1) / (float) width,
                    (bottom + 1) / (float) height};
        }
    }
}
