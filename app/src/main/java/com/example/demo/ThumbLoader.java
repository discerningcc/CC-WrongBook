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
 * 题目列表用的首图缩略图加载器。
 *
 * 为什么需要它：题目的图是拍照原图（可能 3000px），而原来的显示方式是
 * setImageURI(Uri.parse("file://" + path))，等于在主线程按原尺寸解码。
 * 放进 RecyclerView 每次滚动都会重复解码，必然掉帧、内存暴涨。
 *
 * 这里做三件事：
 * 1) 首次用到时在后台把原图解码成 144px 方图，写成一个几 KB 的缩略 JPEG 存进
 *    cache 目录，之后列表只读这个小文件，不再碰原图；
 * 2) 内存里放一个 LRU（按字节计），滚动时不重复读盘；
 * 3) 每个 ImageView 用 setTag(path) 标记，异步结果回来时校验 tag，
 *    防止行被复用后贴到错的位置。
 */
public final class ThumbLoader {

    /** 缩略图边长（px）：列表里按 64dp 显示，144px 足够两倍屏清晰 */
    private static final int THUMB_PX = 144;
    /** 内存缓存上限 8MB，约 40 张缩略图 */
    private static final int MEM_BYTES = 8 * 1024 * 1024;

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

    /** 把原图的缩略图贴到 imageView；传 null 或空串表示这行没有图 */
    public static void bind(Context context, String path, ImageView imageView) {
        imageView.setTag(path);
        if (path == null || path.isEmpty()) {
            imageView.setImageDrawable(null);
            return;
        }

        Bitmap cached = MEM.get(path);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }

        imageView.setImageDrawable(null);   // 先清空，避免复用出上一行的图
        final Context app = context.getApplicationContext();
        POOL.execute(() -> {
            final Bitmap bmp = getOrCreate(app, path);
            if (bmp == null) return;
            MAIN.post(() -> {
                Object tag = imageView.getTag();
                if (tag instanceof String && path.equals(tag)) {
                    imageView.setImageBitmap(bmp);
                }
            });
        });
    }

    /** 行被回收时调用：清 tag，让还在路上的任务失效 */
    public static void release(ImageView imageView) {
        imageView.setTag(null);
        imageView.setImageDrawable(null);
    }

    private static Bitmap getOrCreate(Context context, String path) {
        Bitmap mem = MEM.get(path);
        if (mem != null) return mem;

        File src = new File(path);
        if (!src.isFile()) return null;          // 图片文件不在了，交给 UI 显示占位

        File cache = new File(dir(context), cacheName(src));
        if (cache.isFile()) {
            Bitmap fromDisk = BitmapFactory.decodeFile(cache.getAbsolutePath());
            if (fromDisk != null) {
                MEM.put(path, fromDisk);
                return fromDisk;
            }
            cache.delete();                       // 缩略文件坏了，重建
        }

        Bitmap thumb = makeThumb(src);
        if (thumb == null) return null;
        MEM.put(path, thumb);
        writeThumb(cache, thumb);                 // 写盘失败只影响下次启动，不影响本次显示
        return thumb;
    }

    /** 按 bounds 采样解码 + 中心裁方 + 缩放到 THUMB_PX，避免 OOM */
    private static Bitmap makeThumb(File src) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(src.getAbsolutePath(), bounds);
        int w = bounds.outWidth;
        int h = bounds.outHeight;
        if (w <= 0 || h <= 0) return null;

        int sample = 1;
        int min = Math.min(w, h);
        while (min / (sample * 2) >= THUMB_PX) sample *= 2;

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

        int side = Math.min(bmp.getWidth(), bmp.getHeight());
        int left = (bmp.getWidth() - side) / 2;
        int top = (bmp.getHeight() - side) / 2;

        Bitmap cropped = Bitmap.createBitmap(bmp, left, top, side, side);
        Bitmap scaled = Bitmap.createScaledBitmap(cropped, THUMB_PX, THUMB_PX, true);
        if (scaled != cropped) cropped.recycle();
        if (bmp != scaled) bmp.recycle();
        return scaled;
    }

    private static void writeThumb(File cache, Bitmap bmp) {
        File parent = cache.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) return;

        File tmp = new File(cache.getAbsolutePath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, out);
            out.flush();
        } catch (Exception e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(cache)) tmp.delete();
    }

    /** 缩略文件名带上原图的修改时间与大小，原图变了就会重新生成 */
    private static String cacheName(File src) {
        String raw = src.getAbsolutePath() + "|" + src.lastModified() + "|" + src.length();
        return md5(raw) + ".jpg";
    }

    private static File dir(Context context) {
        if (thumbDir == null) {
            thumbDir = new File(context.getCacheDir(), "thumbs");
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
