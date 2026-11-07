package com.example.demo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
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
     * 把相册 Uri 图片存入应用私有目录：先采样解码到长边上限内再 JPEG 重编码；
     * 解码失败时回退原始字节流直存，不阻断选图。返回私有目录绝对路径。
     */
    public static String saveImageToPrivate(Context context, Uri uri) throws Exception {
        String fileName = UUID.randomUUID().toString() + ".jpg";
        File dir = context.getDir("images", Context.MODE_PRIVATE);
        File outFile = new File(dir, fileName);

        Bitmap bmp = decodeScaled(context, uri, MAX_LONG_EDGE);
        if (bmp != null) {
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos);
            } finally {
                bmp.recycle();
            }
        } else {
            copyRaw(context, uri, outFile);
        }
        return outFile.getAbsolutePath();
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
