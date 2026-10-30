package com.example.demo;

import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.UUID;

public class ImageUtils {
    public static String saveImageToPrivate(Context context, Uri uri) throws Exception {
        String fileName = UUID.randomUUID().toString() + ".jpg";
        File dir = context.getDir("images", Context.MODE_PRIVATE);
        File outFile = new File(dir, fileName);

        InputStream is = context.getContentResolver().openInputStream(uri);
        FileOutputStream fos = new FileOutputStream(outFile);
        byte[] buf = new byte[8192];
        int len;
        while ((len = is.read(buf)) != -1) fos.write(buf, 0, len);
        is.close();
        fos.close();
        return outFile.getAbsolutePath();
    }
}