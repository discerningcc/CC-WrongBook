package com.example.demo;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;

import com.github.chrisbanes.photoview.PhotoView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 复习页 / 题目详情页 / 编辑页共用的图片加载与全屏看图组件。
 *
 * 内联缩略图按目标宽度采样解码，全屏图按屏幕最长边采样解码；解码在后台线程执行，
 * 主线程回贴，ImageView tag 防止视图复用串图。全屏位图尺寸较大，不进内存缓存。
 */
public final class ImageLoader {

    /** 内存缓存上限 24MB，只缓存内联缩略图 */
    private static final int MEM_BYTES = 24 * 1024 * 1024;

    private static final LruCache<String, Bitmap> MEM = new LruCache<String, Bitmap>(MEM_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value == null ? 0 : value.getByteCount();
        }
    };

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "image-loader");
        t.setPriority(Thread.MIN_PRIORITY + 1);
        t.setDaemon(true);
        return t;
    });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ImageLoader() {
    }

    /**
     * 内联缩略图：按 targetWidthPx 等比采样后贴到 iv；文件缺失或解码失败时清空。
     */
    public static void bindThumb(Activity activity, ImageView iv, String path, int targetWidthPx) {
        if (activity == null || iv == null) return;
        String key = (path == null ? "" : path) + "@w" + targetWidthPx;
        iv.setTag(R.id.thumb, key);

        if (path == null || path.isEmpty()) {
            iv.setImageDrawable(null);
            return;
        }

        Bitmap cached = MEM.get(key);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }

        iv.setImageDrawable(null);
        final Context app = activity.getApplicationContext();
        POOL.execute(() -> {
            // 解码宽度留 2 倍余量，贴到 ImageView 时由其等比缩小
            Bitmap bmp = decodeSampled(new File(path), Math.max(300, targetWidthPx * 2));
            if (bmp != null) MEM.put(key, bmp);
            final Bitmap out = bmp;
            MAIN.post(() -> {
                if (key.equals(iv.getTag(R.id.thumb))) {
                    iv.setImageBitmap(out);
                }
            });
        });
    }

    /**
     * 全屏看图：采样到屏幕最长边以内后交给 PhotoView 缩放查看；点按图片关闭。
     * 文件缺失或解码失败时提示并关闭弹窗。
     */
    public static void openFullscreen(final Activity activity, String path) {
        if (activity == null || activity.isFinishing()) return;

        Dialog dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        View view = activity.getLayoutInflater().inflate(R.layout.dialog_photo_view, null);
        final PhotoView photoView = view.findViewById(R.id.photo_view);

        Window window = dialog.getWindow();
        if (window != null) {
            window.getDecorView().setPadding(0, 0, 0, 0);
            window.setBackgroundDrawableResource(android.R.color.black);
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            params.height = WindowManager.LayoutParams.MATCH_PARENT;
            window.setAttributes(params);
        }

        dialog.setContentView(view);
        dialog.show();
        photoView.setOnPhotoTapListener((v, x, y) -> dialog.dismiss());

        if (path == null || path.isEmpty()) {
            Toast.makeText(activity, "图片已不存在", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            return;
        }

        final Context app = activity.getApplicationContext();
        android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        final int screenMax = Math.max(dm.widthPixels, dm.heightPixels);

        POOL.execute(() -> {
            final Bitmap bmp = decodeSampled(new File(path), screenMax);
            MAIN.post(() -> {
                if (activity.isFinishing()) return;
                if (bmp == null) {
                    Toast.makeText(app, "图片已不存在", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                    return;
                }
                photoView.setImageBitmap(bmp);
            });
        });
    }

    /** 按 wantPx 对最长边采样解码；文件不存在、尺寸非法或 OOM 时返回 null。 */
    private static Bitmap decodeSampled(File src, int wantPx) {
        if (src == null || !src.isFile()) return null;

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(src.getAbsolutePath(), bounds);
        int w = bounds.outWidth;
        int h = bounds.outHeight;
        if (w <= 0 || h <= 0) return null;

        int want = Math.max(1, wantPx);
        int sample = 1;
        while (Math.max(w, h) / (sample * 2) >= want) sample *= 2;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.RGB_565;

        try {
            return BitmapFactory.decodeFile(src.getAbsolutePath(), opts);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }
}
