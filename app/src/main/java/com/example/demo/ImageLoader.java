package com.example.demo;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.content.ContentResolver;
import android.util.Log;
import android.util.LruCache;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.github.chrisbanes.photoview.PhotoView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * 复习页 / 题目详情页 / 编辑页共用的图片加载与全屏看图组件。
 *
 * 内联缩略图按目标宽度采样解码，全屏图按屏幕最长边采样解码；解码在后台线程执行，
 * 主线程回贴，ImageView tag 防止视图复用串图。全屏位图尺寸较大，不进内存缓存。
 */
public final class ImageLoader {

    private static final String TAG = "ImageLoader";

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

    /** 列表网格缩略图的磁盘缓存目录（带白边裁剪后的小图） */
    private static File thumbDir;

    /** 当前打开中的看图翻页器与适配器，供采集缩放/平移状态；关闭后清空 */
    private static ViewPager2 sActivePager;
    private static PhotoPagerAdapter sActiveAdapter;

    /** 当前打开中的保存菜单及其展开态；看图关闭时一并收起 */
    private static AlertDialog sSaveMenu;
    private static boolean sSaveMenuOpen;

    /** 大图内删除回调：仅加题页挂载；复习/详情页不传，菜单不出现删除项 */
    public interface OnImageDeleteListener {
        void onDeleteRequested(String path);
    }

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
     * 列表网格首图缩略图：裁掉四周近白边后按列宽等比缩放贴到 iv；带磁盘缓存。
     * 与 {@link #bindThumb} 的区别：网格图需要白边裁剪放大内容，且列宽随横竖屏变化。
     */
    public static void bindGridThumb(Context context, ImageView iv, String path, int targetWidthPx) {
        if (iv == null) return;
        String key = (path == null ? "" : path) + "@g" + targetWidthPx;
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
        final Context app = context.getApplicationContext();
        POOL.execute(() -> {
            final Bitmap bmp = getOrCreateGridThumb(app, path, targetWidthPx);
            if (bmp == null) return;
            MAIN.post(() -> {
                if (key.equals(iv.getTag(R.id.thumb))) {
                    iv.setImageBitmap(bmp);
                }
            });
        });
    }

    /** 行被回收时调用：清 tag，让还在路上的任务失效。 */
    public static void releaseThumb(ImageView iv) {
        iv.setTag(R.id.thumb, null);
        iv.setImageDrawable(null);
    }

    private static Bitmap getOrCreateGridThumb(Context context, String path, int targetW) {
        String key = path + "@g" + targetW;
        Bitmap mem = MEM.get(key);
        if (mem != null) return mem;

        File src = new File(path);
        if (!src.isFile()) return null;

        File cache = new File(gridDir(context), gridCacheName(src, targetW));
        if (cache.isFile()) {
            Bitmap fromDisk = BitmapFactory.decodeFile(cache.getAbsolutePath());
            if (fromDisk != null) {
                MEM.put(key, fromDisk);
                return fromDisk;
            }
            cache.delete();   // 缩略文件坏了，重建
        }

        Bitmap thumb = makeGridThumb(src, targetW);
        if (thumb == null) return null;
        MEM.put(key, thumb);
        writeGridThumb(cache, thumb);
        return thumb;
    }

    /** 采样解码 -> 裁白边 -> 按宽度等比缩放，避免 OOM */
    private static Bitmap makeGridThumb(File src, int targetW) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(src.getAbsolutePath(), bounds);
        int w = bounds.outWidth;
        int h = bounds.outHeight;
        if (w <= 0 || h <= 0) return null;

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

        if (bottom < 0) {   // 整图都是白的，没法裁
            if (small != src) small.recycle();
            return src;
        }

        int pad = 8;   // 分析图上的留白，约还原 10~20px 原图
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

    private static void writeGridThumb(File cache, Bitmap bmp) {
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
    private static String gridCacheName(File src, int targetW) {
        String raw = src.getAbsolutePath() + "|" + src.lastModified() + "|" + src.length() + "|w" + targetW;
        return md5(raw) + ".jpg";
    }

    private static File gridDir(Context context) {
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

    /** 单图全屏看图兼容入口。 */
    public static void openFullscreen(final Activity activity, String path) {
        List<String> paths = new ArrayList<>(1);
        paths.add(path);
        openFullscreen(activity, paths, 0, null, null, false, null);
    }

    /** 多图翻页入口（无关闭回调）。 */
    public static void openFullscreen(final Activity activity, final List<String> paths, int startIndex) {
        openFullscreen(activity, paths, startIndex, null, null, false, null);
    }

    /** 多图翻页入口（无缩放状态恢复）。 */
    public static void openFullscreen(final Activity activity, final List<String> paths, int startIndex,
                                      final Runnable onDismiss) {
        openFullscreen(activity, paths, startIndex, onDismiss, null, false, null);
    }

    /**
     * 采集当前打开看图当前页的缩放/平移矩阵（supp matrix，9 个值）；无打开中的看图时返回 null。
     * 供调用方在 onSaveInstanceState 中留存，旋转重建后回贴。
     */
    public static float[] captureSuppMatrix() {
        if (sActivePager == null || sActiveAdapter == null) return null;
        int pos = sActivePager.getCurrentItem();
        PhotoView pv = sActiveAdapter.viewAt(pos);
        if (pv == null) return null;
        Matrix m = new Matrix();
        pv.getSuppMatrix(m);
        float[] values = new float[9];
        m.getValues(values);
        return values;
    }

    /** 采集保存菜单是否展开；供调用方在 onSaveInstanceState 中留存，旋转重建后重显。 */
    public static boolean captureSaveMenuOpen() {
        return sSaveMenuOpen;
    }

    /** 从当前打开的看图中移除当前页；组内清空则关闭看图。无打开中的看图时忽略。 */
    public static void removeCurrentViewerImage() {
        if (sActiveAdapter != null) sActiveAdapter.removeCurrent();
    }

    /**
     * 全屏看图：同题图片左右翻页，顶部显示 当前/总数 页码；点按图片或周围黑边关闭。
     * 每张图采样到屏幕最长边以内后交给 PhotoView 缩放查看；文件缺失或解码失败该页留空。
     * 弹窗关闭时回调 onDismiss（可为空），供调用方清理「看图打开中」的留存状态。
     * restoreSupp 为旋转重建后需要回贴的缩放/平移矩阵（9 个值，可为空），仅作用于起始页。
     * restoreSaveMenu 为旋转重建后是否需要重新展开保存菜单。
     */
    public static void openFullscreen(final Activity activity, final List<String> paths, int startIndex,
                                      final Runnable onDismiss, final float[] restoreSupp,
                                      final boolean restoreSaveMenu) {
        openFullscreen(activity, paths, startIndex, onDismiss, restoreSupp, restoreSaveMenu, null);
    }

    /**
     * 全屏看图：同题图片左右翻页，顶部显示 当前/总数 页码；点按图片或周围黑边关闭。
     * 每张图采样到屏幕最长边以内后交给 PhotoView 缩放查看；文件缺失或解码失败该页留空。
     * 弹窗关闭时回调 onDismiss（可为空），供调用方清理「看图打开中」的留存状态。
     * restoreSupp 为旋转重建后需要回贴的缩放/平移矩阵（9 个值，可为空），仅作用于起始页。
     * restoreSaveMenu 为旋转重建后是否需要重新展开保存菜单。
     * deleteListener 非空时，大图长按菜单额外提供「删除当前图」；为空则不显示删除项。
     */
    public static void openFullscreen(final Activity activity, final List<String> paths, int startIndex,
                                      final Runnable onDismiss, final float[] restoreSupp,
                                      final boolean restoreSaveMenu,
                                      final OnImageDeleteListener deleteListener) {
        if (activity == null || activity.isFinishing()) return;
        if (paths == null || paths.isEmpty()) {
            Toast.makeText(activity, "图片已不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        // 拷贝一份快照，避免调用方后续重建清空列表影响已打开的看图
        final List<String> snapshot = new ArrayList<>(paths);
        final int size = snapshot.size();
        final int start = Math.max(0, Math.min(startIndex, size - 1));

        Dialog dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        View view = activity.getLayoutInflater().inflate(R.layout.dialog_photo_view, null);
        final ViewPager2 pager = view.findViewById(R.id.photo_pager);
        final LinearLayout dots = view.findViewById(R.id.photo_dots);

        dialog.setOnDismissListener(d -> {
            // 看图关闭时一并收起仍展开的保存菜单
            if (sSaveMenu != null) {
                sSaveMenu.dismiss();
            }
            sActivePager = null;
            sActiveAdapter = null;
            if (onDismiss != null) onDismiss.run();
        });

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

        android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        final int screenMax = Math.max(dm.widthPixels, dm.heightPixels);

        if (size <= 1) {
            dots.setVisibility(View.GONE);
        } else {
            dots.setVisibility(View.VISIBLE);
            final int activeDot = Math.round(10 * dm.density);
            final int inactiveDot = Math.round(8 * dm.density);
            final int dotGap = Math.round(8 * dm.density);
            final List<View> dotViews = new ArrayList<>();
            for (int i = 0; i < dots.getChildCount(); i++) {
                dotViews.add(dots.getChildAt(i));
            }
            final int[] current = {start};
            final Runnable renderDots = () -> {
                for (int i = 0; i < dotViews.size(); i++) {
                    View dot = dotViews.get(i);
                    if (i >= size) {
                        dot.setVisibility(View.GONE);
                        continue;
                    }
                    boolean active = i == current[0];
                    dot.setVisibility(View.VISIBLE);
                    dot.setBackgroundResource(active ? R.drawable.dot_active : R.drawable.dot_inactive);
                    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) dot.getLayoutParams();
                    int d = active ? activeDot : inactiveDot;
                    lp.width = d;
                    lp.height = d;
                    lp.leftMargin = i > 0 ? dotGap : 0;
                    dot.setLayoutParams(lp);
                }
            };
            renderDots.run();
            pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
                @Override
                public void onPageSelected(int position) {
                    current[0] = position;
                    renderDots.run();
                }
            });
        }
        PhotoPagerAdapter adapter = new PhotoPagerAdapter(dialog, snapshot, screenMax, start, restoreSupp,
                activity.getApplicationContext(), () -> snapshot.get(pager.getCurrentItem()), deleteListener);
        pager.setAdapter(adapter);
        adapter.attachPager(pager);
        pager.setCurrentItem(start, false);
        sActivePager = pager;
        sActiveAdapter = adapter;

        dialog.show();

        // 旋转重建后按留存标识重显保存菜单；等视图布局完成后再弹，避免窗口未附着
        if (restoreSaveMenu) {
            pager.post(adapter::showSaveMenu);
        }
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

    /**
     * 把当前图片原样写入系统相册 Pictures/错题本，时间戳命名；返回是否成功。
     * Android 10+ 走 MediaStore（免存储权限），更低版本回退公共目录直写。
     */
    private static boolean saveImageToGallery(Context ctx, File src) throws Exception {
        String ext = extensionOf(src.getName());
        String mime = "png".equals(ext) ? "image/png" : "image/jpeg";
        String baseName = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault())
                .format(new Date());
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, baseName + "." + ext);
        values.put(MediaStore.Images.Media.MIME_TYPE, mime);
        ContentResolver resolver = ctx.getContentResolver();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/错题本");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return false;
            try (OutputStream os = resolver.openOutputStream(uri);
                 FileInputStream is = new FileInputStream(src)) {
                copyBytes(is, os);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES), "错题本");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, baseName + "." + ext);
            try (FileOutputStream fos = new FileOutputStream(out);
                 FileInputStream is = new FileInputStream(src)) {
                copyBytes(is, fos);
            }
            values.clear();
            values.put(MediaStore.Images.Media.DATA, out.getAbsolutePath());
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        }
        return true;
    }

    private static String extensionOf(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "jpg";
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    private static void copyBytes(FileInputStream is, OutputStream os) throws Exception {
        byte[] buf = new byte[8192];
        int len;
        while ((len = is.read(buf)) != -1) {
            os.write(buf, 0, len);
        }
    }

    /** 全屏翻页适配器：每个页面一个 PhotoView，解码在后台线程执行。 */
    private static final class PhotoPagerAdapter extends RecyclerView.Adapter<PhotoPagerAdapter.VH> {

        private final Dialog dialog;
        private final List<String> paths;
        private final int screenMax;
        private final int restorePosition;
        private final float[] restoreSupp;
        private final Context appContext;
        private final Supplier<String> currentPath;
        private final OnImageDeleteListener deleteListener;
        private final SparseArray<PhotoView> views = new SparseArray<>();
        private ViewPager2 pager;

        PhotoPagerAdapter(Dialog dialog, List<String> paths, int screenMax,
                          int restorePosition, float[] restoreSupp,
                          Context appContext, Supplier<String> currentPath,
                          OnImageDeleteListener deleteListener) {
            this.dialog = dialog;
            this.paths = paths;
            this.screenMax = screenMax;
            this.restorePosition = restorePosition;
            this.restoreSupp = restoreSupp;
            this.appContext = appContext;
            this.currentPath = currentPath;
            this.deleteListener = deleteListener;
        }

        void attachPager(ViewPager2 pager) {
            this.pager = pager;
        }

        PhotoView viewAt(int position) {
            return views.get(position);
        }

        void showSaveMenu() {
            List<CharSequence> items = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();
            items.add("保存到本地");
            actions.add(this::saveCurrentImage);
            if (deleteListener != null) {
                items.add("删除当前图");
                actions.add(() -> {
                    String p = currentPath.get();
                    if (p != null && !p.isEmpty()) deleteListener.onDeleteRequested(p);
                });
            }
            AlertDialog menu = new AlertDialog.Builder(dialog.getContext())
                    .setOnDismissListener(d -> {
                        sSaveMenuOpen = false;
                        sSaveMenu = null;
                    })
                    .setItems(items.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run())
                    .show();
            sSaveMenu = menu;
            sSaveMenuOpen = true;
        }

        /** 移除当前页；组内清空则关闭看图。 */
        void removeCurrent() {
            int pos = pager != null ? pager.getCurrentItem() : -1;
            if (pos < 0 || pos >= paths.size()) return;
            paths.remove(pos);
            views.remove(pos);
            notifyItemRemoved(pos);
            if (paths.isEmpty()) {
                dialog.dismiss();
            } else if (pos >= paths.size()) {
                pager.setCurrentItem(paths.size() - 1, false);
            }
        }

        private void saveCurrentImage() {
            String path = currentPath.get();
            if (path == null || path.isEmpty()) {
                MAIN.post(() -> Toast.makeText(appContext, "图片已不存在", Toast.LENGTH_SHORT).show());
                return;
            }
            final File src = new File(path);
            if (!src.isFile()) {
                MAIN.post(() -> Toast.makeText(appContext, "图片已不存在", Toast.LENGTH_SHORT).show());
                return;
            }
            POOL.execute(() -> {
                String msg;
                try {
                    boolean ok = saveImageToGallery(appContext, src);
                    msg = ok ? "已保存到相册" : "保存失败";
                } catch (Exception e) {
                    Log.w(TAG, "save image to gallery failed: " + path, e);
                    msg = "保存失败";
                }
                final String out = msg;
                MAIN.post(() -> Toast.makeText(appContext, out, Toast.LENGTH_SHORT).show());
            });
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            final PhotoView photoView = new PhotoView(parent.getContext());
            photoView.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            // 显式开启缩放并设定级别：适配 1x / 双击中等 2x / 最大 3x
            photoView.setZoomable(true);
            photoView.setScaleLevels(1f, 2f, 3f);
            // 点按视图任意位置（含图片两侧黑边）都关闭弹窗
            photoView.setOnViewTapListener((v, x, y) -> dialog.dismiss());
            // 长按弹出操作菜单，当前项「保存到本地」
            photoView.setOnLongClickListener(v -> {
                showSaveMenu();
                return true;
            });

            TouchGuardFrame frame = new TouchGuardFrame(parent.getContext());
            // ViewPager2 要求页面根布局填满整页
            frame.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            frame.attach(photoView);
            return new VH(frame, photoView);
        }

        @Override
        public void onBindViewHolder(VH holder, int position) {
            final PhotoView photoView = holder.pv;
            views.put(position, photoView);
            photoView.setImageBitmap(null);
            final String path = paths.get(position);
            if (path == null || path.isEmpty()) return;
            POOL.execute(() -> {
                final Bitmap bmp = decodeSampled(new File(path), screenMax);
                MAIN.post(() -> {
                    if (holder.getAdapterPosition() == position) {
                        photoView.setImageBitmap(bmp);
                        // 重建恢复：在布局完成后回贴缩放/平移矩阵
                        if (restoreSupp != null && position == restorePosition) {
                            photoView.post(() -> {
                                photoView.getAttacher().update();
                                Matrix m = new Matrix();
                                m.setValues(restoreSupp);
                                photoView.setSuppMatrix(m);
                            });
                        }
                    }
                });
            });
        }

        @Override
        public int getItemCount() {
            return paths.size();
        }

        static final class VH extends RecyclerView.ViewHolder {
            final PhotoView pv;

            VH(View root, PhotoView pv) {
                super(root);
                this.pv = pv;
            }
        }
    }

    /**
     * 页面包裹层：多指落下时阻止内部 RecyclerView 抢触摸，保证双指捏合缩放完整到达 PhotoView；
     * 翻页器输入始终保持可用，放大后仍可左右翻页。
     */
    private static final class TouchGuardFrame extends FrameLayout {

        TouchGuardFrame(Context context) {
            super(context);
        }

        void attach(PhotoView pv) {
            addView(pv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            // 包裹层自身不拦截，事件全部下发给 PhotoView
            return false;
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            if (ev.getPointerCount() >= 2) {
                ViewParent parent = getParent();
                if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
            }
            return super.dispatchTouchEvent(ev);
        }
    }
}
