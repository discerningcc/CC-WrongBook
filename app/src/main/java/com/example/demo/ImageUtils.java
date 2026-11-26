package com.example.demo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.UUID;

public class ImageUtils {

    /** 落盘图片长边上限（px）；超过则等比缩小 */
    public static final int MAX_LONG_EDGE = 1600;
    /** JPEG 压缩质量 */
    private static final int JPEG_QUALITY = 88;

    /**
     * 把相册 Uri 图片存入应用私有目录：先按 EXIF 方向转正，再采样解码到长边上限内，JPEG 重编码；
     * 解码失败时回退原始字节流直存，不阻断选图。返回私有目录绝对路径。
     */
    public static String saveImageToPrivate(Context context, Uri uri) throws Exception {
        Bitmap bmp = decodeScaled(context, uri, MAX_LONG_EDGE);
        if (bmp == null) {
            File dir = context.getDir("images", Context.MODE_PRIVATE);
            File outFile = new File(dir, UUID.randomUUID().toString() + ".jpg");
            copyRaw(context, uri, outFile);
            return outFile.getAbsolutePath();
        }
        try {
            return writeJpegToPrivate(context, bmp);
        } finally {
            bmp.recycle();
        }
    }

    /**
     * 把 Bitmap 编码成 JPEG 写入私有图片目录，返回新文件绝对路径。
     * 只新写文件、不覆盖任何既有图，调用方按「新文件 + 更新引用」处理；失败时删除半成品。
     */
    public static String writeJpegToPrivate(Context context, Bitmap bmp) throws Exception {
        if (bmp == null) throw new Exception("图片为空");
        File dir = context.getDir("images", Context.MODE_PRIVATE);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("图片目录不可用");
        File outFile = new File(dir, UUID.randomUUID().toString() + ".jpg");
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)) {
                throw new Exception("图片编码失败");
            }
            fos.flush();
        } catch (Exception e) {
            outFile.delete();
            throw e;
        }
        return outFile.getAbsolutePath();
    }

    /**
     * 把已编码好的图片字节直接收进私有图片目录，返回新路径。
     * 用于「编辑时已裁过一次、之后没再转过」的场景：那份 cache 文件就是最终像素，不必再编码一遍。
     */
    public static String adoptFile(Context context, File src) throws Exception {
        if (src == null || !src.isFile()) throw new Exception("图片已不存在");
        File dir = context.getDir("images", Context.MODE_PRIVATE);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("图片目录不可用");
        File outFile = new File(dir, UUID.randomUUID().toString() + ".jpg");
        try (InputStream is = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(outFile)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) fos.write(buf, 0, len);
            fos.flush();
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            outFile.delete();
            throw e;
        }
        return outFile.getAbsolutePath();
    }

    /**
     * 把位图编码成 JPEG 写入 cache 指定子目录，返回文件路径。
     * 编辑页用它留存「裁剪后」的中间结果，Activity 被重建时据此接着编辑。
     */
    public static String writeJpegToCache(Context context, String subDir, Bitmap bmp) throws Exception {
        if (bmp == null) throw new Exception("图片为空");
        File dir = new File(context.getCacheDir(), subDir);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("缓存目录不可用");
        File outFile = new File(dir, UUID.randomUUID().toString() + ".jpg");
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)) {
                throw new Exception("图片编码失败");
            }
            fos.flush();
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            outFile.delete();
            throw e;
        }
        return outFile.getAbsolutePath();
    }

    /**
     * 按「顺时针旋转角度 + 归一化裁剪框」一次生成新图并写盘，返回新文件路径。
     * 编辑页里所有旋转/裁剪都只在预览层叠加，只有这一步真正动像素：整图解码一次、编码一次，
     * 所以连转四次和转一次的画质完全相同。旧图不在这里删，由调用方的引用比对或孤儿图清扫回收。
     *
     * @param angle 0/90/180/270，相对传入的位图顺时针
     * @param crop  0..1 归一化选框，坐标系为「旋转后」的图；null 或整图时不裁
     */
    public static String applyEdit(Context context, Bitmap src, int angle, float[] crop) throws Exception {
        if (src == null) throw new Exception("图片为空");
        int a = ImageEditGeometry.normalizeAngle(angle);
        Bitmap rotated = null;
        Bitmap cropped = null;
        try {
            Bitmap base = src;
            if (a != 0) {
                Matrix m = new Matrix();
                m.postRotate(a);
                rotated = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
                if (rotated == null) throw new Exception("旋转失败");
                base = rotated;
            }
            if (crop != null && !ImageEditGeometry.isFull(crop)) {
                int[] px = ImageEditGeometry.toPixelRect(crop, base.getWidth(), base.getHeight());
                cropped = Bitmap.createBitmap(base, px[0], px[1], px[2] - px[0], px[3] - px[1]);
                if (cropped == null) throw new Exception("裁剪失败");
            }
            return writeJpegToPrivate(context, cropped != null ? cropped : base);
        } finally {
            if (cropped != null) cropped.recycle();
            if (rotated != null && rotated != cropped) rotated.recycle();
        }
    }

    /** 采样解码并等比缩放到 longEdge 以内；文件缺失/解码失败/OOM 返回 null。 */
    private static Bitmap decodeScaled(Context context, Uri uri, int longEdge) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream is = null;
        try {
            is = context.getContentResolver().openInputStream(uri);
            if (is == null) return null;
            BitmapFactory.decodeStream(is, null, bounds);
        } catch (Exception e) {
            return null;
        } finally {
            closeQuietly(is);
        }

        int w = bounds.outWidth;
        int h = bounds.outHeight;
        if (w <= 0 || h <= 0) return null;

        int sample = 1;
        while (Math.max(w, h) / (sample * 2) >= longEdge) sample *= 2;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.RGB_565;

        Bitmap decoded;
        try {
            is = context.getContentResolver().openInputStream(uri);
            if (is == null) return null;
            decoded = BitmapFactory.decodeStream(is, null, opts);
        } catch (OutOfMemoryError e) {
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            closeQuietly(is);
        }
        if (decoded == null) return null;

        // 相册里手机拍的图多数靠方向标记表达朝向，这里先转正，落盘的就是物理正向，
        // 之后任何页面（列表缩略图/详情/复习/导出）都按正常朝向看待。
        decoded = applyExifOrientation(context, uri, decoded);

        int dw = decoded.getWidth();
        int dh = decoded.getHeight();
        int longest = Math.max(dw, dh);
        if (longest <= longEdge) return decoded;

        float scale = (float) longEdge / longest;
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        Bitmap scaled = Bitmap.createBitmap(decoded, 0, 0, dw, dh, m, true);
        if (scaled != decoded) decoded.recycle();
        return scaled;
    }

    /** 读 EXIF 方向标记并按它转正；标记正常或读取失败时原样返回 */
    private static Bitmap applyExifOrientation(Context context, Uri uri, Bitmap src) {
        int tag = readOrientationTag(context, uri);
        if (!ExifOrientation.needsTransform(tag)) return src;
        Matrix m = new Matrix();
        if (ExifOrientation.mirroredFor(tag)) m.postScale(-1, 1);
        int degrees = ExifOrientation.degreesFor(tag);
        if (degrees != 0) m.postRotate(degrees);
        try {
            Bitmap out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
            if (out == null || out == src) return src;
            src.recycle();
            return out;
        } catch (Exception e) {
            return src;
        } catch (OutOfMemoryError e) {
            return src;
        }
    }

    /** 读 EXIF 方向标记；不支持/解析失败一律按「正常」处理，不影响导入 */
    private static int readOrientationTag(Context context, Uri uri) {
        InputStream is = null;
        try {
            is = context.getContentResolver().openInputStream(uri);
            if (is == null) return ExifInterface.ORIENTATION_NORMAL;
            ExifInterface exif = new ExifInterface(is);
            return exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception e) {
            return ExifInterface.ORIENTATION_NORMAL;
        } catch (OutOfMemoryError e) {
            return ExifInterface.ORIENTATION_NORMAL;
        } finally {
            closeQuietly(is);
        }
    }

    /**
     * 采样读取私有目录文件，长边控制在 longEdge 内；编辑页与写盘都走这里。
     * 用 ARGB_8888 保留色深，避免一进编辑就先丢一层色彩。
     */
    public static Bitmap decodeFileScaled(File src, int longEdge) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(src.getAbsolutePath(), bounds);
        int w = bounds.outWidth;
        int h = bounds.outHeight;
        if (w <= 0 || h <= 0) return null;

        int sample = 1;
        while (Math.max(w, h) / (sample * 2) >= longEdge) sample *= 2;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try {
            return BitmapFactory.decodeFile(src.getAbsolutePath(), opts);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    /** 回退：原始字节流直存。 */
    private static void copyRaw(Context context, Uri uri, File outFile) throws Exception {
        try (InputStream is = context.getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(outFile)) {
            if (is == null) throw new Exception("无法读取所选图片");
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) fos.write(buf, 0, len);
        }
    }

    private static void closeQuietly(InputStream is) {
        if (is == null) return;
        try {
            is.close();
        } catch (Exception ignored) {
        }
    }
}
