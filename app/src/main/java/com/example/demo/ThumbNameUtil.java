package com.example.demo;

/**
 * 列表缩略图缓存文件名规则：<原图名去扩展>_<原图修改时间>_w<列宽>.jpg。
 * 名字里带上原图名，才能在原图删了/改了之后把对应的缩略图精确清掉；
 * 纯字符串函数，单独放这里方便 JVM 单测（ImageLoader 一加载就会碰 Android 的 LruCache）。
 */
public final class ThumbNameUtil {

    private ThumbNameUtil() {
    }

    /** 文件名去掉最后一个扩展名，如 a/b/xxx.jpg -> xxx */
    public static String stemOf(String fileName) {
        if (fileName == null) return "";
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        String name = slash >= 0 ? fileName.substring(slash + 1) : fileName;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** 原图对应的缩略文件名 */
    public static String nameOf(String srcFileName, long lastModified, int targetWidth) {
        return stemOf(srcFileName) + "_" + lastModified + "_w" + targetWidth + ".jpg";
    }

    /**
     * 反解缩略文件名：[原图名, 原图修改时间, 列宽]。
     * 对不上本规则的返回 null（老版本用 md5 命名，反推不出原图，只能当过期缓存处理）。
     */
    public static String[] parse(String thumbName) {
        if (thumbName == null || !thumbName.endsWith(".jpg")) return null;
        String base = thumbName.substring(0, thumbName.length() - ".jpg".length());
        int wIdx = base.lastIndexOf("_w");
        if (wIdx <= 0) return null;
        String width = base.substring(wIdx + 2);
        if (!isDigits(width)) return null;
        String rest = base.substring(0, wIdx);
        int mIdx = rest.lastIndexOf('_');
        if (mIdx <= 0) return null;
        String mtime = rest.substring(mIdx + 1);
        if (mtime.length() < 10 || mtime.length() > 13 || !isDigits(mtime)) return null;
        return new String[]{rest.substring(0, mIdx), mtime, width};
    }

    private static boolean isDigits(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }
}
