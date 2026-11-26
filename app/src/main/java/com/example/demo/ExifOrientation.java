package com.example.demo;

/**
 * EXIF 方向标记（1~8）到「转正所需操作」的映射。
 * 抽成纯函数便于 JVM 单测覆盖；真正动 Bitmap 的落地在 {@link ImageUtils}。
 *
 * 标记语义参考 ExifInterface：
 * 1 正常；2 水平镜像；3 旋转 180°；4 垂直镜像（= 镜像 + 180°）；
 * 5 转置（= 镜像 + 90°）；6 旋转 90°；7 反转置（= 镜像 + 270°）；8 旋转 270°。
 */
public final class ExifOrientation {

    private ExifOrientation() {
    }

    /** 转正所需顺时针旋转角度，仅 0/90/180/270，非法或缺失按 0 处理 */
    public static int degreesFor(int tag) {
        switch (tag) {
            case 3:
            case 4:
                return 180;
            case 5:
            case 6:
                return 90;
            case 7:
            case 8:
                return 270;
            default:
                return 0;
        }
    }

    /** 是否还需要水平镜像：2/4/5/7 四种标记含镜像语义 */
    public static boolean mirroredFor(int tag) {
        return tag == 2 || tag == 4 || tag == 5 || tag == 7;
    }

    /** 该标记是否需要动图：正常值与非法值都原样返回 */
    public static boolean needsTransform(int tag) {
        return degreesFor(tag) != 0 || mirroredFor(tag);
    }
}
