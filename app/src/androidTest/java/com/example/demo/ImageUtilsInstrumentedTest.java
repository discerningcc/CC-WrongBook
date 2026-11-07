package com.example.demo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 落盘压缩链路真机回归：大图等比缩到长边上限、小图不被放大、解码失败回退原始字节流。
 * 不依赖系统相册，直接以 file:// Uri 驱动 {@link ImageUtils#saveImageToPrivate}。
 */
@RunWith(AndroidJUnit4.class)
public class ImageUtilsInstrumentedTest {

    private Context context;
    private File imagesDir;
    private final List<File> created = new ArrayList<>();

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        imagesDir = context.getDir("images", Context.MODE_PRIVATE);
    }

    @After
    public void tearDown() {
        for (File f : created) {
            if (f.exists()) f.delete();
        }
        created.clear();
    }

    private File writeBitmap(int w, int h) throws Exception {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        File f = new File(context.getCacheDir(), "test_src_" + w + "x" + h + ".png");
        try (FileOutputStream fos = new FileOutputStream(f)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
        bmp.recycle();
        return f;
    }

    private BitmapFactory.Options bounds(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        return o;
    }

    @Test
    public void largeImage_scaledToMaxLongEdge() throws Exception {
        File src = writeBitmap(3000, 2000);
        Uri uri = Uri.fromFile(src);

        String path = ImageUtils.saveImageToPrivate(context, uri);
        File out = new File(path);
        created.add(out);

        assertTrue("落盘文件应在私有 images 目录",
                out.getParentFile().getCanonicalPath().equals(imagesDir.getCanonicalPath()));
        assertTrue(out.exists());
        assertTrue(out.length() > 0);

        BitmapFactory.Options o = bounds(out);
        assertTrue("输出图应可解码", o.outWidth > 0 && o.outHeight > 0);
        assertTrue("长边不应超过 1600", Math.max(o.outWidth, o.outHeight) <= 1600);
        assertEquals("3000 宽应被缩到 1600 长边", 1600, o.outWidth);
    }

    @Test
    public void smallImage_notEnlarged() throws Exception {
        File src = writeBitmap(800, 600);
        Uri uri = Uri.fromFile(src);

        String path = ImageUtils.saveImageToPrivate(context, uri);
        File out = new File(path);
        created.add(out);

        BitmapFactory.Options o = bounds(out);
        assertEquals(800, o.outWidth);
        assertEquals(600, o.outHeight);
    }

    @Test
    public void undecodableUri_fallsBackToRawBytes() throws Exception {
        String garbage = "this is not an image";
        File src = new File(context.getCacheDir(), "test_src_garbage.bin");
        try (FileOutputStream fos = new FileOutputStream(src)) {
            fos.write(garbage.getBytes(StandardCharsets.UTF_8));
        }
        Uri uri = Uri.fromFile(src);

        String path = ImageUtils.saveImageToPrivate(context, uri);
        File out = new File(path);
        created.add(out);

        assertTrue(out.exists());
        assertEquals("解码失败时应原样回退直存", src.length(), out.length());
    }

    @Test
    public void missingUri_throws() {
        Uri uri = Uri.fromFile(new File(context.getCacheDir(), "test_src_missing.png"));
        try {
            ImageUtils.saveImageToPrivate(context, uri);
            fail("文件不存在时应抛出异常，不允许落盘空文件");
        } catch (Exception e) {
            assertNotNull(e.getMessage());
        }
    }
}
