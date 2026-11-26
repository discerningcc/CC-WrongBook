package com.example.demo;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 图片编辑页：左转 / 右转各 90°，以及裁剪；裁剪会按设置自动去白边（默认开）。
 *
 * 几个刻意定下的口径：
 * 1. 位图只解码一次；旋转不改像素，只记角度并用 ImageView 的矩阵显示，所以连转多少次都不掉画质。
 * 2. 裁剪选框是 0..1 归一化坐标，跟着图一起转；确认裁剪时才真正切出位图，并写一份 cache 供跨重建恢复。
 * 3. 只有点「完成」才把结果写进私有图片目录；点「取消」或返回键退出，原图一个字节都不动。
 * 4. 没做任何改动就点「完成」，回传原路径并标记未改动，调用方据此跳过替换与落库。
 * 5. 裁剪模式下「完成」置灰：裁剪是一个必须先收口的子流程，避免"带着未确认选框提交"的歧义。
 */
public class ImageEditActivity extends AppCompatActivity {
    private static final String TAG = "ImageEditActivity";

    public static final String EXTRA_PATH = "edit_path";
    public static final String EXTRA_RESULT_PATH = "edit_result_path";
    /** 结果里回带原路径，调用方据此知道该替换哪一张 */
    public static final String EXTRA_RESULT_SOURCE = "edit_result_source";
    public static final String EXTRA_CHANGED = "edit_changed";

    private static final String KEY_ANGLE = "edit_angle";
    private static final String KEY_CROP = "edit_crop";
    private static final String KEY_CROPPING = "edit_cropping";
    private static final String KEY_PENDING = "edit_pending";
    private static final String KEY_SOURCE_PATH = "edit_source_path";

    /** 进裁剪时选框四边内缩的比例：手柄贴边不好抓 */
    private static final float CROP_INSET = 0.10f;
    private static final String CACHE_DIR = "edit";

    private ImageView imageView;
    private CropOverlayView overlay;
    private ProgressBar loading;
    private View editBar, cropBar;
    private View btnDone, btnRotateLeft, btnRotateRight, btnCrop,
            btnCropReset, btnCropCancel, btnCropConfirm;

    /** 原图路径：取消编辑时原图完全不动，靠的就是只读它 */
    private String sourcePath;
    /** 当前像素：原图，或上一次确认裁剪的结果 */
    private Bitmap source;
    /** 当前像素对应的 cache 文件；null 表示还没裁过，像素就是原图 */
    private String pendingPath;
    /** 相对原图的顺时针累计角度，只影响显示与最终落盘 */
    private int angle;
    /** 裁剪选框，仅在裁剪模式下有意义 */
    private float[] crop = ImageEditGeometry.fullRect();
    /** 进入裁剪前的选框，取消裁剪时还原 */
    private float[] cropSnapshot;
    private boolean cropping;
    private boolean busy;

    private final ExecutorService BG = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "image-edit-bg");
        t.setDaemon(true);
        return t;
    });

    /** 构造编辑页 Intent，调用方自己用 ActivityResultLauncher 启动。 */
    public static Intent newIntent(Context context, String path) {
        Intent i = new Intent(context, ImageEditActivity.class);
        i.putExtra(EXTRA_PATH, path);
        return i;
    }

    /** 从结果 Intent 取新路径；null 表示没有结果 */
    public static String resultPath(Intent data) {
        return data == null ? null : data.getStringExtra(EXTRA_RESULT_PATH);
    }

    /** 从结果 Intent 查是否真的改动过；未改动时调用方不必替换路径 */
    public static boolean resultChanged(Intent data) {
        return data != null && data.getBooleanExtra(EXTRA_CHANGED, false);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_edit);

        imageView = findViewById(R.id.edit_image);
        overlay = findViewById(R.id.crop_overlay);
        loading = findViewById(R.id.edit_loading);
        editBar = findViewById(R.id.edit_bar);
        cropBar = findViewById(R.id.crop_bar);
        btnDone = findViewById(R.id.edit_done);
        btnRotateLeft = findViewById(R.id.act_rotate_left);
        btnRotateRight = findViewById(R.id.act_rotate_right);
        btnCrop = findViewById(R.id.act_crop);
        btnCropReset = findViewById(R.id.act_crop_reset);
        btnCropCancel = findViewById(R.id.act_crop_cancel);
        btnCropConfirm = findViewById(R.id.act_crop_confirm);

        findViewById(R.id.edit_cancel).setOnClickListener(v -> onCancel());
        btnDone.setOnClickListener(v -> onDone());
        btnRotateLeft.setOnClickListener(v -> rotateBy(-90));
        btnRotateRight.setOnClickListener(v -> rotateBy(90));
        btnCrop.setOnClickListener(v -> enterCrop());
        btnCropReset.setOnClickListener(v -> onCropReset());
        btnCropCancel.setOnClickListener(v -> exitCropMode(true));
        btnCropConfirm.setOnClickListener(v -> onCropConfirm());

        if (savedInstanceState != null) {
            // 重建：角度、选框、是否在裁剪、以及裁剪结果的 cache 都留存过，接着编辑
            sourcePath = savedInstanceState.getString(KEY_SOURCE_PATH);
            angle = ImageEditGeometry.normalizeAngle(savedInstanceState.getInt(KEY_ANGLE, 0));
            float[] savedCrop = savedInstanceState.getFloatArray(KEY_CROP);
            crop = savedCrop != null && savedCrop.length == 4 ? savedCrop : ImageEditGeometry.fullRect();
            cropping = savedInstanceState.getBoolean(KEY_CROPPING);
            pendingPath = savedInstanceState.getString(KEY_PENDING);
        } else {
            sourcePath = getIntent().getStringExtra(EXTRA_PATH);
        }

        // 视图尺寸要等一帧，先把布局变化挂上，尺寸一到位就摆正图片
        imageView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) applyPreview();
        });

        switchBars();
        pruneCache();
        loadSource();
    }

    /** 清掉上次异常退出（进程被杀）留下的中间图，别让 cache/edit 一直堆着；当前在用的那份跳过 */
    private void pruneCache() {
        File[] olds = new File(getCacheDir(), CACHE_DIR).listFiles();
        if (olds == null) return;
        for (File f : olds) {
            if (pendingPath != null && pendingPath.equals(f.getAbsolutePath())) continue;
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    // ==================== 载入 ====================

    /** 解码当前像素：优先读裁剪结果的 cache（重建时存在），否则读原图 */
    private void loadSource() {
        final String path = pendingPath != null && new File(pendingPath).isFile() ? pendingPath : sourcePath;
        if (path == null || path.isEmpty() || !new File(path).isFile()) {
            Toast.makeText(this, "图片已不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        loading.setVisibility(View.VISIBLE);
        BG.execute(() -> {
            Bitmap bmp = ImageUtils.decodeFileScaled(new File(path), ImageUtils.MAX_LONG_EDGE);
            runOnUiThread(() -> {
                loading.setVisibility(View.GONE);
                if (isFinishing() || isDestroyed()) {
                    if (bmp != null) bmp.recycle();
                    return;
                }
                if (bmp == null) {
                    Toast.makeText(this, "图片读取失败", Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                source = bmp;
                applyPreview();
            });
        });
    }

    /**
     * 按当前角度把图片摆进舞台：旋转后包围盒等比缩放填满，居中显示。
     * 同时把显示矩形交给裁剪浮层，选框按它换算像素。
     */
    private void applyPreview() {
        if (source == null) return;
        int vw = imageView.getWidth();
        int vh = imageView.getHeight();
        if (vw <= 0 || vh <= 0) {
            imageView.post(this::applyPreview);
            return;
        }

        int w = source.getWidth();
        int h = source.getHeight();
        boolean swap = angle == 90 || angle == 270;
        float bw = swap ? h : w;
        float bh = swap ? w : h;
        float scale = Math.min(vw / bw, vh / bh);

        Matrix m = new Matrix();
        // 先绕位图中心旋转，再等比缩放到容器内，最后把中心挪到容器中心
        m.setRotate(angle, w / 2f, h / 2f);
        m.postScale(scale, scale, w / 2f, h / 2f);
        m.postTranslate(vw / 2f - w / 2f, vh / 2f - h / 2f);

        imageView.setImageBitmap(source);
        imageView.setImageMatrix(m);

        float dw = bw * scale;
        float dh = bh * scale;
        overlay.setGeometry(new RectF(vw / 2f - dw / 2f, vh / 2f - dh / 2f,
                vw / 2f + dw / 2f, vh / 2f + dh / 2f));
        overlay.setCrop(crop);
    }

    // ==================== 旋转 ====================

    private void rotateBy(int step) {
        if (source == null || busy || cropping) return;
        angle = ImageEditGeometry.rotate(angle, step);
        applyPreview();
    }

    // ==================== 裁剪 ====================

    /**
     * 进裁剪。设置里「图片自动去白边」开着（默认开）时，先在后台按当前显示方向分析像素，
     * 把选框收紧到内容上再进裁剪态；关掉就直接给四边内缩的初始框。
     * 白边本来就少（内容已占 92% 以上）或整图全白时，退回内缩框，不打断、不提示。
     */
    private void enterCrop() {
        if (source == null || busy || cropping) return;
        if (!SettingsActivity.isAutoTrimEnabled(this)) {
            enterCropWith(ImageEditGeometry.insetRect(CROP_INSET));
            return;
        }
        final Bitmap src = source;
        final int a = angle;
        setBusy(true);
        BG.execute(() -> {
            float[] trimmed = null;
            Bitmap small = null;
            Bitmap oriented = null;
            try {
                int aw = Math.min(AutoTrim.ANALYSIS_WIDTH, src.getWidth());
                int ah = Math.max(1, Math.round(src.getHeight() * (float) aw / src.getWidth()));
                small = Bitmap.createScaledBitmap(src, aw, ah, false);
                if (small == null) throw new IllegalStateException("缩放失败");
                // 选框是相对"当前显示方向"的，所以分析图得先按角度转过来，否则 90°/270° 时框会横竖错位
                if (a != 0) {
                    Matrix m = new Matrix();
                    m.postRotate(a);
                    oriented = Bitmap.createBitmap(small, 0, 0, small.getWidth(), small.getHeight(), m, true);
                    if (oriented == null) throw new IllegalStateException("旋转失败");
                } else {
                    oriented = small;
                }
                int ow = oriented.getWidth();
                int oh = oriented.getHeight();
                int[] px = new int[ow * oh];
                oriented.getPixels(px, 0, ow, 0, 0, ow, oh);
                AutoTrim.Result res = AutoTrim.find(px, ow, oh);
                if (res.changed) trimmed = res.toCrop();
            } catch (Exception e) {
                Log.w(TAG, "去白边分析失败", e);
            } catch (OutOfMemoryError e) {
                Log.w(TAG, "去白边内存不足", e);
            } finally {
                if (oriented != null && oriented != small && oriented != src) oriented.recycle();
                if (small != null && small != src) small.recycle();
            }

            final float[] box = trimmed;
            runOnUiThread(() -> {
                setBusy(false);
                if (isFinishing() || isDestroyed()) return;
                enterCropWith(box != null ? box : ImageEditGeometry.insetRect(CROP_INSET));
            });
        });
    }

    /** 以给定选框进裁剪态；已在裁剪态时只更新框，不重记快照 */
    private void enterCropWith(float[] rect) {
        if (source == null || busy) return;
        if (!cropping) {
            // 记下进入前的选框：取消裁剪要原样还原（选框此刻恒为整图，留一份是为了语义清楚）
            cropSnapshot = new float[]{crop[0], crop[1], crop[2], crop[3]};
        }
        cropping = true;
        crop = ImageEditGeometry.clamp(new float[]{rect[0], rect[1], rect[2], rect[3]},
                ImageEditGeometry.MIN_SIDE, true, true);
        overlay.setCrop(crop);
        overlay.setEditable(true);
        switchBars();
    }

    private void onCropReset() {
        if (!cropping) return;
        crop = ImageEditGeometry.insetRect(CROP_INSET);
        overlay.setCrop(crop);
    }

    /** 退出裁剪模式；restore 为 true 表示取消裁剪，选框还原为进入前的样子 */
    private void exitCropMode(boolean restore) {
        cropping = false;
        if (restore && cropSnapshot != null) {
            crop = new float[]{cropSnapshot[0], cropSnapshot[1], cropSnapshot[2], cropSnapshot[3]};
        }
        overlay.setEditable(false);
        switchBars();
        applyPreview();
    }
    /**
     * 确认裁剪：真正切出像素，替换当前像素并把结果写进 cache 供重建恢复。
     * 选框就是整图时等效于什么都没裁，直接退回编辑模式。
     */
    private void onCropConfirm() {
        if (source == null || busy || !cropping) return;
        final float[] rect = overlay.getCrop();
        if (ImageEditGeometry.isFull(rect)) {
            exitCropMode(false);
            return;
        }

        final int a = angle;
        final Bitmap src = source;
        setBusy(true);
        BG.execute(() -> {
            Bitmap out = null;
            String cachePath = null;
            try {
                Bitmap rotated = null;
                Bitmap base = src;
                if (a != 0) {
                    Matrix m = new Matrix();
                    m.postRotate(a);
                    rotated = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
                    if (rotated == null) throw new Exception("旋转失败");
                    base = rotated;
                }
                int[] px = ImageEditGeometry.toPixelRect(rect, base.getWidth(), base.getHeight());
                Bitmap cut = Bitmap.createBitmap(base, px[0], px[1], px[2] - px[0], px[3] - px[1]);
                if (cut == null) throw new Exception("裁剪失败");
                // 独立一份像素：createBitmap 可能返回与源共享内存的切片，之后要回收源图
                out = cut.copy(Bitmap.Config.ARGB_8888, false);
                if (cut != base) cut.recycle();
                if (rotated != null) rotated.recycle();
                if (out == null) throw new Exception("裁剪失败");
                cachePath = ImageUtils.writeJpegToCache(this, CACHE_DIR, out);
            } catch (Exception e) {
                Log.w(TAG, "裁剪失败", e);
            } catch (OutOfMemoryError e) {
                Log.w(TAG, "裁剪内存不足", e);
            }
            final Bitmap result = out;
            final String cache = cachePath;
            runOnUiThread(() -> {
                setBusy(false);
                if (isFinishing() || isDestroyed()) {
                    if (result != null) result.recycle();
                    return;
                }
                if (result == null || cache == null) {
                    Toast.makeText(this, "裁剪失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                Bitmap old = source;
                source = result;
                angle = 0;
                crop = ImageEditGeometry.fullRect();
                deleteQuietly(pendingPath);
                pendingPath = cache;
                exitCropMode(false);
                if (old != null && old != source) old.recycle();
            });
        });
    }

    // ==================== 完成 / 取消 ====================

    private void onDone() {
        if (source == null || busy || cropping) return;
        final int a = angle;
        final float[] c = crop;
        // 没转过也没裁过：原样回传，不产生新文件
        if (a == 0 && ImageEditGeometry.isFull(c) && pendingPath == null) {
            deliver(sourcePath, false);
            return;
        }
        // 中途裁过且没再转：裁剪结果本身就是最终像素，直接收进私有目录，省一次重编码
        if (a == 0 && ImageEditGeometry.isFull(c) && pendingPath != null) {
            setBusy(true);
            final String pending = pendingPath;
            BG.execute(() -> {
                String out = null;
                try {
                    out = ImageUtils.adoptFile(this, new File(pending));
                } catch (Exception e) {
                    Log.w(TAG, "编辑结果落地失败", e);
                }
                final String path = out;
                runOnUiThread(() -> {
                    setBusy(false);
                    if (path == null) {
                        Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    deliver(path, true);
                });
            });
            return;
        }

        setBusy(true);
        BG.execute(() -> {
            String out = null;
            try {
                out = ImageUtils.applyEdit(this, source, a, c);
            } catch (Exception e) {
                Log.w(TAG, "编辑后写盘失败", e);
            } catch (OutOfMemoryError e) {
                Log.w(TAG, "编辑内存不足", e);
            }
            final String path = out;
            runOnUiThread(() -> {
                setBusy(false);
                if (path == null) {
                    Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                deliver(path, true);
            });
        });
    }

    private void onCancel() {
        if (busy) return;
        setResult(RESULT_CANCELED);
        finish();
    }

    private void deliver(String path, boolean changed) {
        Intent data = new Intent();
        data.putExtra(EXTRA_RESULT_PATH, path);
        data.putExtra(EXTRA_RESULT_SOURCE, sourcePath);
        data.putExtra(EXTRA_CHANGED, changed);
        setResult(RESULT_OK, data);
        finish();
    }

    @Override
    public void onBackPressed() {
        if (cropping) {
            // 返回键先退裁剪，不直接丢掉整个编辑
            exitCropMode(true);
            return;
        }
        super.onBackPressed();
    }

    // ==================== 状态 ====================

    /** 工具条随模式切换；裁剪模式下屏蔽旋转与完成，避免带着未确认的选框提交 */
    private void switchBars() {
        editBar.setVisibility(cropping ? View.GONE : View.VISIBLE);
        cropBar.setVisibility(cropping ? View.VISIBLE : View.GONE);
        btnDone.setEnabled(!cropping);
        btnDone.setAlpha(cropping ? 0.35f : 1f);
        if (cropping) {
            // 裁剪条里没有旋转入口，浮层可编辑
            overlay.setEditable(true);
        } else {
            overlay.setEditable(false);
        }
    }

    private void setBusy(boolean on) {
        busy = on;
        loading.setVisibility(on ? View.VISIBLE : View.GONE);
        btnDone.setEnabled(!on && !cropping);
        btnDone.setAlpha((on || cropping) ? 0.35f : 1f);
        btnRotateLeft.setEnabled(!on);
        btnRotateRight.setEnabled(!on);
        btnCrop.setEnabled(!on);
        btnCropReset.setEnabled(!on);
        btnCropCancel.setEnabled(!on);
        btnCropConfirm.setEnabled(!on);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(KEY_SOURCE_PATH, sourcePath);
        outState.putString(KEY_PENDING, pendingPath);
        outState.putInt(KEY_ANGLE, angle);
        outState.putFloatArray(KEY_CROP, crop);
        outState.putBoolean(KEY_CROPPING, cropping);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 真正退出（完成或取消）才清理中间文件；配置变化重建时要留着给下一份实例读
        if (isFinishing()) deleteQuietly(pendingPath);
        if (!busy && source != null) {
            source.recycle();
            source = null;
        }
    }

    private static void deleteQuietly(String path) {
        if (path == null) return;
        try {
            //noinspection ResultOfMethodCallIgnored
            new File(path).delete();
        } catch (Exception ignored) {
        }
    }
}
