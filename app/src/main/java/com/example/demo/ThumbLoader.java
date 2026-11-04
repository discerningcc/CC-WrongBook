package com.example.demo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 题目列表用的首图缩略图加载器（网格版）。
 *
 * 与旧版的区别：
 * 1) 不再裁成正方形，而是按列宽等比缩放整图，高度跟随原图比例；
 * 2) 生成前先裁掉四周的白边（截图类题目白边占比很大，裁掉后图内文字
 *    实际放大约 1.5~2 倍，列表里才看得清）；
 * 3) 按列宽 targetWidthPx 出图，宽度写进缓存文件名，横竖屏列数不同
 *    时互不串缓存。
 */
public final class ThumbLoader {

    /** 内存缓存上限 24MB，约 20 张网格图 */
    private static final int MEM_BYTES = 24 * 1024 * 1024;

    private static final LruCache<String, Bitmap> MEM = new LruCache<String, Bitmap>(MEM_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value == null ? 0 : value.getByteCount();
        }
    };

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "thumb-loader");
        t.setPriority(Thread.MIN_PRIORITY + 1);
        t.setDaemon(true);
        return t;
    });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static File thumbDir;

    private ThumbLoader() {
    }

    /**
     * 把原图按列宽贴到 imageView。
     * @param targetWidthPx 网格列的实际宽度（px），缩略图宽度会缩到这个尺寸
     */
    public static void bind(Context context, String path, ImageView imageView, int targetWidthPx) {
        Object tag = imageView.getTag(R.id.thumb) == null ? null : imageView.getTag(R.id.thumb);
        // tag 同时记下 path 和目标宽度，宽度变了（旋转）也要重新加载
        imageView.setTag(R.id.thumb, path == null ? "" : path + "@" + targetWidthPx);
        if (path == null || path.isEmpty()) {
            imageView.setImageDrawable(null);
            return;
        }

        String key = path + "@" + targetWidthPx;
        Bitmap cached = MEM.get(key);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }

        imageView.setImageDrawable(null);   // 先清空，避免复用出上一行的图
        final Context app = context.getApplicationContext();
        POOL.execute(() -> {
            final Bitmap bmp = getOrCreate(app, path, targetWidthPx);
            if (bmp == null) return;
            MAIN.post(() -> {
                Object cur = imageView.getTag(R.id.thumb);
                if (key.equals(cur)) {
                    imageView.setImageBitmap(bmp);
                }
            });
        });
    }

    /** 行被回收时调用：清 tag，让还在路上的任务失效 */
    public static void release(ImageView imageView) {
        imageView.setTag(R.id.thumb, null);
        imageView.setImageDrawable(null);
    }

    private static Bitmap getOrCreate(Context context, String path, int targetW) {
        String key = path + "@" + targetW;
        Bitmap mem = MEM.get(key);
        if (mem != null) return mem;

        File src = new File(path);
        if (!src.isFile()) return null;          // 图片文件不在了，交给 UI 显示占位

        File cache = new File(dir(context), cacheName(src, targetW));
        if (cache.isFile()) {
            Bitmap fromDisk = BitmapFactory.decodeFile(cache.getAbsolutePath());
            if (fromDisk != null) {
                MEM.put(key, fromDisk);
                return fromDisk;
            }
            cache.delete();                       // 缩略文件坏了，重建
        }

        Bitmap thumb = makeThumb(src, targetW);
        if (thumb == null) return null;
        MEM.put(key, thumb);
        writeThumb(cache, thumb);                 // 写盘失败只影响下次启动，不影响本次显示
        return thumb;
    }

    /** 按 bounds 采样解码 -> 裁白边 -> 按宽度等比缩放，避免 OOM */
    private static Bitmap makeThumb(File src, int targetW) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(src.getAbsolutePath(), bounds);
        int w = bounds.outWidth;
        int h = bounds.outHeight;
        if (w <= 0 || h <= 0) return null;

        // 采样到 decoded 宽度不超过 targetW 的 2 倍，既省内存又保留裁切余量
        int want = Math.max(300, targetW * 2);
        int sample = 1;
        while (w / (sample * 2) >= want) sample *= 2;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.RGB_565;

        Bitmap bmp;
        try {
            bmp = BitmapFactory.decodeFile(src.getAbsolutePath(), opts);
        } catch (OutOfMemoryError e) {
            return null;
        }
        if (bmp == null) return null;

        Bitmap trimmed = trimWhite(bmp);
        int tw = trimmed.getWidth();
        int th = trimmed.getHeight();
        int newH = Math.max(1, Math.round(th * (float) targetW / tw));

        Bitmap scaled = Bitmap.createScaledBitmap(trimmed, targetW, newH, true);
        if (scaled != trimmed) trimmed.recycle();
        if (bmp != scaled) bmp.recycle();
        return scaled;
    }

    /**
     * 裁掉四周接近纯白的边。分析图缩到 200px 宽跑一遍像素，找到内容外接框，
     * 回原图坐标裁回。内容已经占满（白边很少）时原样返回。
     */
    private static Bitmap trimWhite(Bitmap src) {
        int aw = Math.min(200, src.getWidth());
        int ah = Math.max(1, src.getHeight() * aw / src.getWidth());
        Bitmap small = Bitmap.createScaledBitmap(src, aw, ah, false);
        int[] px = new int[aw * ah];
        small.getPixels(px, 0, aw, 0, 0, aw, ah);

        int top = ah, bottom = -1, left = aw, right = -1;
        for (int y = 0; y < ah; y++) {
            int row = y * aw;
            for (int x = 0; x < aw; x++) {
                int p = px[row + x];
                int r = (p >> 16) & 0xff;
                int g = (p >> 8) & 0xff;
                int b = p & 0xff;
                // 任一分量低于 238 就算有内容（灰文字、浅阴影都算）
                if (r < 238 || g < 238 || b < 238) {
                    if (y < top) top = y;
                    if (y > bottom) bottom = y;
                    if (x < left) left = x;
                    if (x > right) right = x;
                }
            }
        }

        if (bottom < 0) {                        // 整图都是白的，没法裁
            if (small != src) small.recycle();
            return src;
        }

        int pad = 8;                             // 分析图上的留白，约还原 10~20px 原图
        int l = Math.max(0, left - pad);
        int t = Math.max(0, top - pad);
        int r = Math.min(aw - 1, right + pad);
        int b = Math.min(ah - 1, bottom + pad);

        // 内容框已经占 92% 以上，裁了也没收益
        float box = (float) ((r - l + 1) * (b - t + 1)) / (aw * ah);
        if (box >= 0.92f) {
            if (small != src) small.recycle();
            return src;
        }

        float sx = (float) src.getWidth() / aw;
        float sy = (float) src.getHeight() / ah;
        int ox = Math.round(l * sx);
        int oy = Math.round(t * sy);
        int ow = Math.round((r - l + 1) * sx);
        int oh = Math.round((b - t + 1) * sy);
        ow = Math.min(ow, src.getWidth() - ox);
        oh = Math.min(oh, src.getHeight() - oy);

        Bitmap out = Bitmap.createBitmap(src, ox, oy, ow, oh);
        if (small != src) small.recycle();
        return out;
    }

    private static void writeThumb(File cache, Bitmap bmp) {
        File parent = cache.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) return;

        File tmp = new File(cache.getAbsolutePath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out);
            out.flush();
        } catch (Exception e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(cache)) tmp.delete();
    }

    /** 缩略文件名带原图修改时间、大小和目标宽度，原图或列宽变了就重新生成 */
    private static String cacheName(File src, int targetW) {
        String raw = src.getAbsolutePath() + "|" + src.lastModified() + "|" + src.length() + "|w" + targetW;
        return md5(raw) + ".jpg";
    }

    private static File dir(Context context) {
        if (thumbDir == null) {
            thumbDir = new File(context.getCacheDir(), "thumbs_grid");
            if (!thumbDir.isDirectory()) thumbDir.mkdirs();
        }
        return thumbDir;
    }

    private static String md5(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(raw.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }
}
