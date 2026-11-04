package com.example.demo;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 题库存储：支持多个题库（saves/ 目录，每题库一个 JSON 文件），
 * 通过 SharedPreferences 记录「当前题库」，所有读写自动作用于当前题库。
 * 旧版单文件 questions_data.txt 首次启动自动迁移为「我的题库」。
 * 导入 / 导出使用 ZIP 格式（questions.json + images/ 图片），图片路径在导出时
 * 转为相对路径、导入时重写为本机私有目录绝对路径。
 */
public class QuestionStorage {

    private static final String PREFS = "settings";
    private static final String KEY_CURRENT_BANK = "current_save_id";
    private static final String KEY_MIGRATED = "data_migrated_v2";
    private static final String OLD_FILE = "questions_data.txt";
    private static final String SAVES_DIR = "saves";
    private static final String IMAGES_DIR = "images";
    private static final String EXPORT_JSON = "questions.json";

    /** 当前题库缓存（含题目列表） */
    private static QuestionBank cacheBank = null;

    // ==================== 后台写盘 ====================
    // 答题时只改内存里的题目对象，真正的 JSON 序列化和文件写入交给这个单线程执行器，
    // 主线程不再为 78 KB 的整份题库买单；连续多次改动会用「最新快照」覆盖前一份，不会堆积。

    private static final Object WRITE_LOCK = new Object();
    /** 待写快照：bankId → 最新一份拷贝 */
    private static final Map<String, QuestionBank> pendingWrites = new HashMap<>();
    private static boolean writeRunning = false;

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bank-writer");
        t.setPriority(Thread.MIN_PRIORITY + 1);   // 别和界面抢 CPU
        return t;
    });

    // ==================== 对外兼容的旧接口（自动走当前题库） ====================

    public static List<Question> loadAll(Context context) {
        ensureBankExists(context);
        return cacheBank.questions;
    }

    /** 删除单题（同时删除该题关联的图片文件） */
    public static void delete(Context context, Question question) {
        ensureBankExists(context);
        cacheBank.questions.remove(question);
        write(context);
        deleteQuestionImages(context, question);
    }

    /** 保存一条新题 */
    public static void save(Context context, Question question) {
        ensureBankExists(context);
        cacheBank.questions.add(question);
        write(context);
    }

    /** 保存当前题库的改动（状态更新等）：只写内存，落盘在后台线程完成 */
    public static void update(Context context) {
        ensureBankExists(context);
        write(context);
    }

    /** 今日待复习（自动根据设置选择模式） */
    public static List<Question> getTodayReview(Context context) {
        List<Question> result = new ArrayList<>();
        long now = System.currentTimeMillis();
        long oneDay = 1000L * 60 * 60 * 24;

        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean naturalDayEnabled = sp.getBoolean(SettingsActivity.KEY_NATURAL_DAY, true);

        for (Question q : loadAll(context)) {
            long days;
            if (naturalDayEnabled) {
                // 自然日模式 → 使用【本地时区】计算天数
                days = getDayNumberLocal(now) - getDayNumberLocal(q.updateTime);
            } else {
                // 满24小时模式
                days = (now - q.updateTime) / oneDay;
            }
            boolean need = false;
            switch (q.status) {
                case "E": need = true; break;
                case "D": need = days >= 3; break;
                case "C": need = days >= 7; break;
                case "B": need = days >= 15; break;
                case "A": need = false; break;
            }
            if (need) result.add(q);
        }
        return result;
    }

    public static Question findById(Context context, String qid) {
        for (Question q : loadAll(context)) {
            if (qid != null && qid.equals(q.id)) return q;
        }
        return null;
    }

    private static long getDayNumberLocal(long timestamp) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeZone(java.util.TimeZone.getDefault());
        cal.setTimeInMillis(timestamp);
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis() / (1000L * 60 * 60 * 24);
    }

    // ==================== 题库管理接口 ====================

    /** 列表展示用的题库摘要 */
    public static class BankInfo {
        public String id;
        public String name;
        public long createdAt;
        public long updatedAt;
        public int count;
        public boolean current;
    }

    /** 列出全部题库（新的在前） */
    public static List<BankInfo> listBanks(Context context) {
        ensureBankExists(context);
        flush(context);   // 保证列表里的名称/时间是最新的
        List<BankInfo> list = new ArrayList<>();
        String currentId = readCurrentBankId(context);
        File[] files = savesDir(context).listFiles((d, n) -> n.endsWith(".json"));
        if (files != null) {
            Arrays.sort(files, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Long.compare(b.lastModified(), a.lastModified());
                }
            });
            for (File f : files) {
                QuestionBank b = readBankFile(f);
                if (b == null) continue;
                BankInfo info = new BankInfo();
                info.id = b.id;
                info.name = b.name;
                info.createdAt = b.createdAt;
                info.updatedAt = b.updatedAt;
                info.count = b.questions == null ? 0 : b.questions.size();
                info.current = b.id.equals(currentId);
                list.add(info);
            }
        }
        return list;
    }

    /** 新建题库并切换为当前 */
    public static void createBank(Context context, String name) {
        ensureBankExists(context);
        QuestionBank b = createBankInternal(context, name);
        setCurrentBankId(context, b.id);
        cacheBank = b;
    }

    /** 切换到指定题库 */
    public static void switchBank(Context context, String bankId) {
        ensureBankExists(context);
        flush(context);   // 先把当前题库没落盘的改动写完，再读新题库
        if (bankFile(context, bankId).exists()) {
            setCurrentBankId(context, bankId);
            cacheBank = readBankFile(bankFile(context, bankId));
            if (cacheBank == null) {
                cacheBank = new QuestionBank();
                cacheBank.id = bankId;
                cacheBank.name = "未命名题库";
            }
        }
    }

    /** 当前题库名称 */
    public static String getCurrentBankName(Context context) {
        ensureBankExists(context);
        return cacheBank.name;
    }

    public static void renameBank(Context context, String bankId, String newName) {
        if (newName == null || newName.trim().isEmpty()) return;
        QuestionBank b = readBankFile(bankFile(context, bankId));
        if (b == null) return;
        b.name = newName.trim();
        b.updatedAt = System.currentTimeMillis();
        if (b.id.equals(readCurrentBankId(context))) {
            cacheBank = b;
        }
        requestWrite(context, b);   // 改名不常用，但也交给后台线程写
    }

    /** 删除题库（连同其题目图片；若删的是当前题库则自动切到下一个或新建） */
    public static void deleteBank(Context context, String bankId) {
        ensureBankExists(context);
        flush(context);   // 先写完再删，避免写完最后一份又把它写回来
        QuestionBank b = readBankFile(bankFile(context, bankId));
        if (b != null && b.questions != null) {
            for (Question q : b.questions) deleteQuestionImages(context, q);
        }
        synchronized (WRITE_LOCK) {
            pendingWrites.remove(bankId);
        }
        bankFile(context, bankId).delete();
        if (bankId.equals(readCurrentBankId(context))) {
            setCurrentBankId(context, null);
            cacheBank = null;
            ensureBankExists(context);
        }
    }

    /** 进度回调（在后台线程调用，done=已完成数量，total=总数量） */
    public interface ProgressListener {
        void onProgress(int done, int total);
    }

    /** 导出某个题库为 zip（questions.json + images/），写入用户选择的 Uri */
    public static void exportBankToUri(Context context, String bankId, Uri targetUri) throws Exception {
        exportBankToUri(context, bankId, targetUri, null);
    }

    /** 导出某个题库为 zip，带进度回调 */
    public static void exportBankToUri(Context context, String bankId, Uri targetUri, ProgressListener listener) throws Exception {
        QuestionBank b = readBankFile(bankFile(context, bankId));
        if (b == null) throw new Exception("题库不存在，导出失败");

        QuestionBank exportBank = new QuestionBank();
        exportBank.id = b.id;
        exportBank.name = b.name;
        exportBank.createdAt = b.createdAt;
        exportBank.updatedAt = b.updatedAt;
        exportBank.questions = new ArrayList<>();

        // 绝对路径 → zip 内相对路径（images/文件名）
        Map<String, String> imageMap = new HashMap<>();
        for (Question q : b.questions) {
            exportBank.questions.add(cloneWithRelativeImages(q, imageMap));
        }

        // 统计实际存在的图片数（用于进度）
        int total = 0;
        for (String p : imageMap.keySet()) {
            if (new File(p).exists()) total++;
        }
        if (listener != null) listener.onProgress(0, total);

        OutputStream os = context.getContentResolver().openOutputStream(targetUri);
        if (os == null) throw new Exception("无法打开目标文件");
        ZipOutputStream zos = new ZipOutputStream(os);
        try {
            zos.putNextEntry(new ZipEntry(EXPORT_JSON));
            zos.write(new Gson().toJson(exportBank).getBytes("UTF-8"));
            zos.closeEntry();

            int done = 0;
            for (Map.Entry<String, String> e : imageMap.entrySet()) {
                File f = new File(e.getKey());
                if (!f.exists()) continue;
                zos.putNextEntry(new ZipEntry(e.getValue()));
                FileInputStream fis = new FileInputStream(f);
                try {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = fis.read(buf)) != -1) zos.write(buf, 0, len);
                } finally {
                    fis.close();
                }
                zos.closeEntry();
                done++;
                if (listener != null) listener.onProgress(done, total);
            }
        } finally {
            zos.close();
        }
    }

    /** 导入 zip 题库文件（重名自动加「（2）」），返回导入后的题库 */
    public static QuestionBank importBank(Context context, Uri uri) throws Exception {
        File tmpDir = new File(context.getCacheDir(), "import_" + System.currentTimeMillis());
        tmpDir.mkdirs();
        try {
            InputStream is = context.getContentResolver().openInputStream(uri);
            if (is == null) throw new Exception("无法读取所选文件");
            ZipInputStream zis = new ZipInputStream(is);
            try {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    File out = new File(tmpDir, entry.getName());
                    if (entry.isDirectory()) {
                        out.mkdirs();
                    } else {
                        out.getParentFile().mkdirs();
                        FileOutputStream fos = new FileOutputStream(out);
                        try {
                            byte[] buf = new byte[8192];
                            int len;
                            while ((len = zis.read(buf)) != -1) fos.write(buf, 0, len);
                        } finally {
                            fos.close();
                        }
                    }
                    zis.closeEntry();
                }
            } finally {
                zis.close();
            }

            File jsonFile = new File(tmpDir, EXPORT_JSON);
            if (!jsonFile.exists()) throw new Exception("不是有效的题库文件（缺少 questions.json）");
            QuestionBank bank = new Gson().fromJson(readFile(jsonFile), QuestionBank.class);
            if (bank == null || bank.questions == null) throw new Exception("题库文件解析失败");

            bank.name = uniqueBankName(context,
                    (bank.name == null || bank.name.trim().isEmpty()) ? "导入题库" : bank.name.trim());
            bank.id = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();
            bank.createdAt = now;
            bank.updatedAt = now;

            // 图片重写：zip 内相对路径 → 本机私有目录绝对路径（加题库 id 前缀防重名）
            File imagesDir = context.getDir(IMAGES_DIR, Context.MODE_PRIVATE);
            Map<String, String> pathMap = new HashMap<>();
            for (Question q : bank.questions) {
                q.contentImages = rewriteImagePaths(imagesDir, bank.id, q.contentImages, tmpDir, pathMap);
                q.answerImages = rewriteImagePaths(imagesDir, bank.id, q.answerImages, tmpDir, pathMap);
            }
            writeBankNow(context, bank);
            return bank;
        } finally {
            deleteRecursive(tmpDir);
        }
    }

    // ==================== 内部实现 ====================

    /** 确保存在一个可用的当前题库（含旧数据迁移与兜底新建） */
    private static void ensureBankExists(Context context) {
        if (cacheBank != null) return;

        String currentId = readCurrentBankId(context);
        if (currentId != null && bankFile(context, currentId).exists()) {
            cacheBank = readBankFile(bankFile(context, currentId));
            if (cacheBank != null) return;
        }

        migrateIfNeeded(context);
        if (cacheBank != null) return;

        File[] files = savesDir(context).listFiles((d, n) -> n.endsWith(".json"));
        if (files != null && files.length > 0) {
            String first = files[0].getName().replace(".json", "");
            setCurrentBankId(context, first);
            cacheBank = readBankFile(bankFile(context, first));
            if (cacheBank != null) return;
        }

        cacheBank = createBankInternal(context, "我的题库");
        setCurrentBankId(context, cacheBank.id);
    }

    /** 旧版单文件数据 → 迁移为「我的题库」（只迁移一次） */
    private static void migrateIfNeeded(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp.getBoolean(KEY_MIGRATED, false)) return;

        File old = new File(context.getFilesDir(), OLD_FILE);
        if (!old.exists()) {
            sp.edit().putBoolean(KEY_MIGRATED, true).apply();
            return;
        }
        try {
            String json = readFile(old);
            Type type = new TypeToken<List<Question>>() {}.getType();
            List<Question> questions = new Gson().fromJson(json, type);
            if (questions == null) questions = new ArrayList<>();

            QuestionBank bank = new QuestionBank();
            bank.id = UUID.randomUUID().toString();
            bank.name = "我的题库";
            long now = System.currentTimeMillis();
            bank.createdAt = now;
            bank.updatedAt = now;
            bank.questions = questions;
            writeBankNow(context, bank);   // 一次性迁移，留在当前线程写完再继续
            setCurrentBankId(context, bank.id);
            cacheBank = bank;
            sp.edit().putBoolean(KEY_MIGRATED, true).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static QuestionBank createBankInternal(Context context, String name) {
        QuestionBank b = new QuestionBank();
        b.id = UUID.randomUUID().toString();
        b.name = (name == null || name.trim().isEmpty()) ? "未命名题库" : name.trim();
        long now = System.currentTimeMillis();
        b.createdAt = now;
        b.updatedAt = now;
        b.questions = new ArrayList<>();
        writeBankNow(context, b);   // 新题库是个空文件，写完再返回，保证文件确实存在
        return b;
    }

    /** 重名自动加「（2）」「（3）」… */
    private static String uniqueBankName(Context context, String name) {
        Set<String> names = new HashSet<>();
        for (BankInfo info : listBanks(context)) names.add(info.name);
        if (!names.contains(name)) return name;
        int i = 2;
        while (names.contains(name + "（" + i + "）")) i++;
        return name + "（" + i + "）";
    }

    private static Question cloneWithRelativeImages(Question q, Map<String, String> imageMap) {
        Question nq = new Question(q.content, q.answer);
        nq.id = q.id;
        nq.status = q.status;
        nq.updateTime = q.updateTime;
        nq.contentImages = new ArrayList<>();
        if (q.contentImages != null) {
            for (String p : q.contentImages) nq.contentImages.add(toRelativePath(p, imageMap));
        }
        nq.answerImages = new ArrayList<>();
        if (q.answerImages != null) {
            for (String p : q.answerImages) nq.answerImages.add(toRelativePath(p, imageMap));
        }
        return nq;
    }

    private static String toRelativePath(String absPath, Map<String, String> imageMap) {
        if (absPath == null || absPath.isEmpty()) return absPath;
        String rel = imageMap.get(absPath);
        if (rel == null) {
            rel = "images/" + new File(absPath).getName();
            imageMap.put(absPath, rel);
        }
        return rel;
    }

    /** 把 zip 内的相对图片路径重写为本地私有目录绝对路径 */
    private static List<String> rewriteImagePaths(File imagesDir, String bankId, List<String> paths,
                                                  File tmpDir, Map<String, String> pathMap) throws IOException {
        List<String> result = new ArrayList<>();
        if (paths == null) return result;
        for (String rel : paths) {
            if (rel == null || rel.isEmpty()) continue;
            String newPath = pathMap.get(rel);
            if (newPath == null) {
                File src = new File(tmpDir, rel);
                if (!src.exists()) continue; // 图片缺失则跳过，不阻断导入
                File dest = new File(imagesDir, bankId + "_" + src.getName());
                int i = 1;
                while (dest.exists()) {
                    dest = new File(imagesDir, bankId + "_" + src.getName() + "_" + (i++));
                }
                copyFile(src, dest);
                newPath = dest.getAbsolutePath();
                pathMap.put(rel, newPath);
            }
            result.add(newPath);
        }
        return result;
    }

    /** 删除题目关联的所有图片（仅限应用私有 images 目录内） */
    private static void deleteQuestionImages(Context context, Question question) {
        try {
            File imagesDir = context.getDir(IMAGES_DIR, Context.MODE_PRIVATE);
            if (question.contentImages != null) {
                for (String path : question.contentImages) deleteImage(imagesDir, path);
            }
            if (question.answerImages != null) {
                for (String path : question.answerImages) deleteImage(imagesDir, path);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void deleteImage(File imagesDir, String path) {
        if (path == null || path.isEmpty()) return;
        File f = new File(path);
        if (!f.exists()) return;
        try {
            if (f.getCanonicalPath().startsWith(imagesDir.getCanonicalPath())) {
                f.delete();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ==================== 文件 IO ====================

    private static File savesDir(Context context) {
        return context.getDir(SAVES_DIR, Context.MODE_PRIVATE);
    }

    private static File bankFile(Context context, String bankId) {
        return new File(savesDir(context), bankId + ".json");
    }

    private static void writeBankNow(Context context, QuestionBank bank) {
        File target = bankFile(context, bank.id);
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try {
            FileOutputStream fos = new FileOutputStream(tmp);
            try {
                fos.write(new Gson().toJson(bank).getBytes("UTF-8"));
            } finally {
                fos.close();
            }
            // 先写临时文件再改名：中途崩溃时原题库文件仍然完好
            if (!tmp.renameTo(target)) {
                if (target.exists()) target.delete();
                if (!tmp.renameTo(target)) throw new IOException("rename failed: " + target);
            }
        } catch (IOException e) {
            tmp.delete();
            e.printStackTrace();
        }
    }

    private static QuestionBank readBankFile(File f) {
        try {
            return new Gson().fromJson(readFile(f), QuestionBank.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static String readFile(File f) throws IOException {
        FileInputStream fis = new FileInputStream(f);
        try {
            byte[] buffer = new byte[(int) f.length()];
            int off = 0;
            while (off < buffer.length) {
                int n = fis.read(buffer, off, buffer.length - off);
                if (n < 0) break;
                off += n;
            }
            return new String(buffer, 0, off, "UTF-8");
        } finally {
            fis.close();
        }
    }

    private static void copyFile(File src, File dest) throws IOException {
        FileInputStream fis = new FileInputStream(src);
        try {
            FileOutputStream fos = new FileOutputStream(dest);
            try {
                byte[] buf = new byte[8192];
                int len;
                while ((len = fis.read(buf)) != -1) fos.write(buf, 0, len);
            } finally {
                fos.close();
            }
        } finally {
            fis.close();
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        f.delete();
    }

    /** 读当前题库 id（不触发初始化，供内部调用，避免递归） */
    private static String readCurrentBankId(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CURRENT_BANK, null);
    }

    /** 当前题库 id（对外接口，会确保题库已完成初始化） */
    public static String getCurrentBankId(Context context) {
        ensureBankExists(context);
        return readCurrentBankId(context);
    }

    private static void setCurrentBankId(Context context, String bankId) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CURRENT_BANK, bankId).apply();
    }

    /** 写当前题库：主线程只刷新修改时间，序列化与落盘交给后台线程 */
    private static void write(Context context) {
        ensureBankExists(context);
        cacheBank.updatedAt = System.currentTimeMillis();
        requestWrite(context, cacheBank);
    }

    /**
     * 请求把某个题库写盘（异步）。
     * 调用方立刻返回，主线程只花"复制一份快照"的微秒级开销；
     * 写线程取最新快照落盘，之前的旧快照被覆盖，所以连点也不会堆积成很多次整份写。
     */
    private static void requestWrite(Context context, QuestionBank bank) {
        if (context == null || bank == null || bank.id == null) return;
        final Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        synchronized (WRITE_LOCK) {
            pendingWrites.put(bank.id, snapshotOf(bank));
            if (writeRunning) return;
            writeRunning = true;
        }
        WRITER.execute(new Runnable() {
            @Override
            public void run() {
                drainWrites(app);
            }
        });
    }

    /** 写线程主循环：把待写快照一个接一个写掉，直到没有新改动 */
    private static void drainWrites(Context app) {
        while (true) {
            QuestionBank snap;
            synchronized (WRITE_LOCK) {
                if (pendingWrites.isEmpty()) {
                    writeRunning = false;
                    return;
                }
                snap = pendingWrites.values().iterator().next();
                pendingWrites.remove(snap.id);
            }
            writeBankNow(app, snap);
        }
    }

    /**
     * 立即把还没落盘的改动写完（切题库、暂停、退出前调用）。
     * 靠单线程写队列的先后顺序：这里排一个空任务，它执行完就说明前面排队的写都已完成。
     */
    public static void flush(Context context) {
        if (context == null) return;
        synchronized (WRITE_LOCK) {
            boolean idle = !writeRunning && pendingWrites.isEmpty();
            if (idle) return;
        }
        final CountDownLatch done = new CountDownLatch(1);
        WRITER.execute(new Runnable() {
            @Override
            public void run() {
                done.countDown();
            }
        });
        try {
            done.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 复制当前题库，供后台线程安全地序列化（界面线程还在继续改原对象） */
    private static QuestionBank snapshotOf(QuestionBank src) {
        QuestionBank copy = new QuestionBank();
        copy.id = src.id;
        copy.name = src.name;
        copy.createdAt = src.createdAt;
        copy.updatedAt = src.updatedAt;
        copy.questions = new ArrayList<>();
        if (src.questions != null) {
            for (Question q : src.questions) {
                if (q != null) copy.questions.add(q.copy());
            }
        }
        return copy;
    }
}
