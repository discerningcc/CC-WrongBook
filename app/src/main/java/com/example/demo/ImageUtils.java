package com.example.demo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.UUID;

public class ImageUtils {

    /** 落盘图片长边上限（px）；超过则等比缩小 */
    private static final int MAX_LONG_EDGE = 1600;
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
     * 把私有目录里的图片顺时针旋转 90°，输出成一个新的正向文件并返回其路径。
     * 不原地覆盖：旧图由题库引用比对或孤儿图清扫回收，避免写盘失败时把原图毁掉。
     * 解码用 ARGB_8888 保留色深，尺寸不超过落盘上限，连续旋转不会一路缩小。
     */
    public static String rotate90(Context context, String srcPath) throws Exception {
        if (srcPath == null || srcPath.isEmpty()) throw new Exception("图片已不存在");
        File src = new File(srcPath);
        if (!src.isFile()) throw new Exception("图片已不存在");
        Bitmap bmp = decodeFileScaled(src, MAX_LONG_EDGE);
        if (bmp == null) throw new Exception("图片解码失败");
        Bitmap rotated = null;
        try {
            Matrix m = new Matrix();
            m.postRotate(90);
            rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (rotated == null) throw new Exception("旋转失败");
            return writeJpegToPrivate(context, rotated);
        } finally {
            if (rotated != null && rotated != bmp) rotated.recycle();
            bmp.recycle();
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

    /** 采样读取私有目录文件，长边控制在 longEdge 内；旋转用，保留 ARGB_8888 色深 */
    private static Bitmap decodeFileScaled(File src, int longEdge) {
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
