package com.example.demo;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

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
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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

    private static final String TAG = "QuestionStorage";
    private static final String PREFS = "settings";
    private static final String KEY_CURRENT_BANK = "current_save_id";
    private static final String KEY_MIGRATED = "data_migrated_v2";
    private static final String KEY_MIGRATED_V3 = "data_migrated_v3";
    private static final String KEY_MIGRATED_V4 = "data_migrated_v4";
    private static final String KEY_MIGRATED_V5 = "data_migrated_v5";
    public static final String KEY_DAILY_BUDGET = "daily_review_budget";
    public static final int DEFAULT_DAILY_BUDGET = 50;
    /** 今日已完成题数（按自然日跨天清零） */
    private static final String KEY_PROGRESS_DATE = "today_progress_day";
    private static final String KEY_PROGRESS_DONE = "today_progress_done";
    /** S 级长期抽查间隔（天） */
    public static final int S_INTERVAL_DAYS = 30;
    private static final String OLD_FILE = "questions_data.txt";
    private static final String SAVES_DIR = "saves";
    private static final String IMAGES_DIR = "images";
    private static final String EXPORT_JSON = "questions.json";
    private static final String CHECKINS_JSON = "checkins.json";

    /** 当前题库缓存（含题目列表） */
    private static QuestionBank cacheBank = null;

    /** 加题页草稿期引用的图片路径：孤儿清理不得删除；保存/放弃后清空 */
    private static final Set<String> sDraftRetained = new HashSet<>();

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
        if (question == null) return;
        cacheBank.questions.remove(question);
        write(context);
        deleteQuestionImages(context, question);
    }

    /** 删除单张私有图片：仅当未被当前题库任何题目引用、也不在草稿保留集合中时才删文件，避免误删共享图/草稿图 */
    public static void deletePrivateImageIfUnused(Context context, String path) {
        if (path == null || path.isEmpty()) return;
        synchronized (WRITE_LOCK) {
            if (sDraftRetained.contains(path)) return;
        }
        ensureBankExists(context);
        for (Question q : cacheBank.questions) {
            if (containsImage(q.contentImages, path) || containsImage(q.answerImages, path)) return;
        }
        File imagesDir = context.getDir(IMAGES_DIR, Context.MODE_PRIVATE);
        deleteImage(imagesDir, path);
    }

    /** 登记加题页草稿期引用的图片，清理孤儿图时保留；入参传当前两组路径集合 */
    public static void retainDraftImages(List<String> paths) {
        synchronized (WRITE_LOCK) {
            sDraftRetained.clear();
            if (paths != null) sDraftRetained.addAll(paths);
        }
    }

    /** 保存成功或放弃退出后清空草稿保留集合 */
    public static void clearDraftRetained() {
        synchronized (WRITE_LOCK) {
            sDraftRetained.clear();
        }
    }

    private static boolean containsImage(List<String> images, String path) {
        return images != null && images.contains(path);
    }

    /**
     * 启动时扫描私有图片目录，删除未被任何题库题目引用、也不在草稿保留集合中的孤儿文件。
     * 保留集遍历 saves/ 下全部题库，避免误删其他题库正在使用的图；删除前仍做目录归属校验。
     */
    public static void sweepOrphanImages(Context context) {
        File imagesDir = context.getDir(IMAGES_DIR, Context.MODE_PRIVATE);
        Set<String> kept = new HashSet<>();
        synchronized (WRITE_LOCK) {
            addKeptPath(kept, sDraftRetained);
        }
        File[] bankFiles = savesDir(context).listFiles((d, n) -> n.endsWith(".json"));
        if (bankFiles != null) {
            for (File f : bankFiles) {
                QuestionBank b = readBankFile(f);
                if (b == null || b.questions == null) continue;
                for (Question q : b.questions) {
                    addKeptPath(kept, q.contentImages);
                    addKeptPath(kept, q.answerImages);
                }
            }
        }
        File[] imgs = imagesDir.listFiles();
        if (imgs == null) return;
        for (File img : imgs) {
            if (!img.isFile()) continue;
            String canonical;
            try {
                canonical = img.getCanonicalPath();
            } catch (IOException e) {
                canonical = img.getAbsolutePath();
            }
            if (!kept.contains(canonical)) {
                deleteImage(imagesDir, canonical);
            }
        }
    }

    private static void addKeptPath(Set<String> kept, Collection<String> paths) {
        if (paths == null) return;
        for (String p : paths) {
            if (p == null || p.isEmpty()) continue;
            try {
                kept.add(new File(p).getCanonicalPath());
            } catch (IOException e) {
                kept.add(p);
            }
        }
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

    /** 斩题：软标记退出复习队列与长期抽查，题目本体保留，可恢复 */
    public static void excludeQuestion(Context context, Question q) {
        ensureBankExists(context);
        if (q == null) return;
        q.excluded = true;
        write(context);
    }

    /** 恢复斩题：取消标记并打回 E，重新走遗忘曲线 */
    public static void restoreQuestion(Context context, Question q) {
        ensureBankExists(context);
        if (q == null) return;
        q.excluded = false;
        q.updateStatus("E");
        write(context);
    }

    /** 重学本题：打回 E 级并刷新更新时间，重新进入复习计划；斩题标记保持不变 */
    public static void restudyQuestion(Context context, Question q) {
        ensureBankExists(context);
        if (q == null) return;
        q.updateStatus("E");
        write(context);
    }

    /** 收藏 / 取消收藏：仅翻转星标，不影响等级、斩题与复习队列 */
    public static void toggleFavorite(Context context, Question q) {
        ensureBankExists(context);
        if (q == null) return;
        q.favorite = !q.favorite;
        write(context);
    }

    /** 等级徽章颜色：E 灰 → D 绿 → C 蓝 → B 紫 → A 橙 → S 红 */
    public static int statusColor(String status) {
        switch (status == null ? "E" : status) {
            case "S": return 0xfff44336;   // 红
            case "A": return 0xffff9800;   // 橙
            case "B": return 0xff9c27b0;   // 紫
            case "C": return 0xff2196f3;   // 蓝
            case "D": return 0xff4caf50;   // 绿
            default: return 0xff9e9e9e;    // E 灰
        }
    }

    /** 今日全部到期的题（不截断；用于统计、筛选）。开 session 用 buildTodayQueue */
    public static List<Question> getTodayReview(Context context) {
        List<Question> result = new ArrayList<>();
        for (Question q : loadAll(context)) {
            if (isDue(context, q)) result.add(q);
        }
        return result;
    }

    /** 每日复习预算（设置页可调，默认 50） */
    public static int getDailyBudget(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_DAILY_BUDGET, DEFAULT_DAILY_BUDGET);
    }

    public static void setDailyBudget(Context context, int n) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_DAILY_BUDGET, n).apply();
    }

    /** 自然日模式开关（默认开）；关闭后为 24 小时滚动间隔，打卡功能不可用 */
    public static boolean isNaturalDayEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(SettingsActivity.KEY_NATURAL_DAY, true);
    }

    /** 打卡总开关（默认开）；仅自然日模式下可在设置页切换 */
    public static boolean isCheckinEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(SettingsActivity.KEY_CHECKIN_ENABLED, true);
    }

    public static void setCheckinEnabled(Context context, boolean on) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(SettingsActivity.KEY_CHECKIN_ENABLED, on).apply();
    }

    /** 今天已经按目标完成的题数（跨自然日自动清零） */
    public static int getTodayDone(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long today = getDayNumberLocal(System.currentTimeMillis());
        if (sp.getLong(KEY_PROGRESS_DATE, -1) != today) return 0;
        return sp.getInt(KEY_PROGRESS_DONE, 0);
    }

    /** 答完一道（记得/模糊/不会）后计数；跳过和答错重做的第二遍不调用这里 */
    public static void incTodayDone(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long today = getDayNumberLocal(System.currentTimeMillis());
        SharedPreferences.Editor e = sp.edit();
        if (sp.getLong(KEY_PROGRESS_DATE, -1) != today) {
            e.putLong(KEY_PROGRESS_DATE, today).putInt(KEY_PROGRESS_DONE, 0);
        }
        e.putInt(KEY_PROGRESS_DONE, sp.getInt(KEY_PROGRESS_DONE, 0) + 1).apply();
    }

    /** 今日目标还剩多少额度；不限量（budget<=0）返回很大的数 */
    public static int getRemainingBudget(Context context) {
        int budget = getDailyBudget(context);
        if (budget <= 0) return Integer.MAX_VALUE;
        return Math.max(0, budget - getTodayDone(context));
    }

    /**
     * 今日实际进 session 的队列：
     * 1) E / 逾期 D、C、B、A 优先（E 先排，其余按最久没更新）；
     * 2) S 级长期抽查补位：至少 1 道、至多预算的 5%；
     * 3) 总量不超过日预算，多出来的年轻题和 S 题顺延到后面几天（不丢）。
     */
    public static List<Question> buildTodayQueue(Context context) {
        int budget = getDailyBudget(context);
        List<Question> young = new ArrayList<>();
        List<Question> sList = new ArrayList<>();
        for (Question q : loadAll(context)) {
            if (!isDue(context, q)) continue;
            if ("S".equals(q.status)) sList.add(q);
            else young.add(q);
        }
        // 年轻题：E 优先，其余按 updateTime 升序（最久没碰的先）
        Collections.sort(young, new Comparator<Question>() {
            @Override
            public int compare(Question a, Question b) {
                int ra = "E".equals(a.status) ? 0 : 1;
                int rb = "E".equals(b.status) ? 0 : 1;
                if (ra != rb) return Integer.compare(ra, rb);
                return Long.compare(a.updateTime, b.updateTime);
            }
        });
        Collections.sort(sList, new Comparator<Question>() {
            @Override
            public int compare(Question a, Question b) {
                return Long.compare(a.updateTime, b.updateTime);
            }
        });

        List<Question> queue = new ArrayList<>();

        // budget <= 0 = 不限量：今天到期多少做多少（仍按 E优先/S殿后排序）
        if (budget <= 0) {
            queue.addAll(young);
            queue.addAll(sList);
            return queue;
        }

        // 预算 = 今日目标：队列只装到「预算 - 今日已完成」为止；答满目标返回空队列
        int remaining = getRemainingBudget(context);
        if (remaining <= 0) return queue;

        int sCap = Math.max(1, (int) (remaining * 0.05f));
        int sTake = Math.min(sList.size(), sCap);
        int youngTake = Math.min(young.size(), remaining - sTake);

        queue.addAll(young.subList(0, youngTake));
        queue.addAll(sList.subList(0, sTake));
        return queue;
    }

    /** 该题今天是否该复习：已斩题永不进队列；E 每天到期；其余按等级间隔 */
    public static boolean isDue(Context context, Question q) {
        if (q == null) return false;
        if (q.excluded) return false;
        int interval = intervalDaysOf(q.status);
        if (interval == 0) return true;   // E：随时都该复习
        return daysSince(context, q) >= interval;
    }

    /** 等级对应的复习间隔（天）：E=0，D=1，C=3，B=7，A=15，S=30（艾宾浩斯节奏） */
    public static int intervalDaysOf(String status) {
        if (status == null) return 0;
        switch (status) {
            case "D": return 1;
            case "C": return 3;
            case "B": return 7;
            case "A": return 15;
            case "S": return S_INTERVAL_DAYS;
            default: return 0;   // E
        }
    }

    /** 低一级的等级（模糊档按低一级间隔算） */
    public static String lowerLevelOf(String status) {
        if (status == null) return "E";
        switch (status) {
            case "D": return "E";
            case "C": return "D";
            case "B": return "C";
            case "A": return "B";
            case "S": return "A";
            default: return "E";
        }
    }

    /** 距上次更新过了多少天（跟随设置页的“自然日 / 满 24 小时”开关） */
    public static long daysSince(Context context, Question q) {
        if (q == null) return 0;
        long now = System.currentTimeMillis();
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp.getBoolean(SettingsActivity.KEY_NATURAL_DAY, true)) {
            return getDayNumberLocal(now) - getDayNumberLocal(q.updateTime);
        }
        return (now - q.updateTime) / (1000L * 60 * 60 * 24);
    }

    /** 题目列表里展示的到期文案 */
    public static String dueText(Context context, Question q) {
        if (q != null && q.excluded) return "已斩";
        int interval = intervalDaysOf(q.status);
        if (interval == 0) return "待复习";
        long left = interval - daysSince(context, q);
        if (left <= 0) return "今天到期";
        return left + " 天后到期";
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

            // 打卡记录随包备份；无记录时不写条目
            String checkinsJson = CheckinStorage.exportJson(context);
            if (checkinsJson != null && !checkinsJson.isEmpty()) {
                zos.putNextEntry(new ZipEntry(CHECKINS_JSON));
                zos.write(checkinsJson.getBytes("UTF-8"));
                zos.closeEntry();
            }

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

    /** 导入结果：题库本体 + 本次并入的打卡记录条数。 */
    public static class ImportResult {
        public QuestionBank bank;
        public int checkinCount;
    }

    /** 导入 zip 题库文件（重名自动加「（2）」），返回导入后的题库 */
    public static QuestionBank importBank(Context context, Uri uri) throws Exception {
        return importBank(context, uri, null);
    }

    /** 导入 zip 题库文件，带进度回调（done/total，解压条目数与题目处理数两段上报） */
    public static QuestionBank importBank(Context context, Uri uri, ProgressListener listener) throws Exception {
        return importBankWithResult(context, uri, listener).bank;
    }

    /** 导入 zip 题库文件，返回题库本体与并入打卡条数。 */
    public static ImportResult importBankWithResult(Context context, Uri uri, ProgressListener listener) throws Exception {
        File tmpDir = new File(context.getCacheDir(), "import_" + System.currentTimeMillis());
        tmpDir.mkdirs();
        try {
            // 先枚举压缩包条目数，供解压阶段展示进度（ZipInputStream 只能顺序读，需先探一遍）
            int totalEntries = 0;
            InputStream probe = null;
            try {
                probe = context.getContentResolver().openInputStream(uri);
                if (probe != null) {
                    ZipInputStream pz = new ZipInputStream(probe);
                    try {
                        ZipEntry pe;
                        while ((pe = pz.getNextEntry()) != null) {
                            if (!pe.isDirectory()) totalEntries++;
                            pz.closeEntry();
                        }
                    } finally {
                        pz.close();
                    }
                }
            } catch (Exception ignored) {
                totalEntries = 0;
            }
            if (listener != null) listener.onProgress(0, totalEntries);

            int doneEntries = 0;
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
                    doneEntries++;
                    if (listener != null) listener.onProgress(doneEntries, totalEntries);
                }
            } finally {
                zis.close();
            }

            File jsonFile = new File(tmpDir, EXPORT_JSON);
            if (!jsonFile.exists()) throw new Exception("不是有效的题库文件（缺少 questions.json）");
            QuestionBank bank = new Gson().fromJson(readFile(jsonFile), QuestionBank.class);
            if (bank == null || bank.questions == null) throw new Exception("题库文件解析失败");

            bank.name = uniqueBankName(context, resolveImportBaseName(context, uri, bank.name));
            bank.id = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();
            bank.createdAt = now;
            bank.updatedAt = now;

            // 图片重写：zip 内相对路径 → 本机私有目录绝对路径（加题库 id 前缀防重名）
            File imagesDir = context.getDir(IMAGES_DIR, Context.MODE_PRIVATE);
            Map<String, String> pathMap = new HashMap<>();
            int totalQ = bank.questions.size();
            if (listener != null) listener.onProgress(0, totalQ);
            int doneQ = 0;
            for (Question q : bank.questions) {
                q.contentImages = rewriteImagePaths(imagesDir, bank.id, q.contentImages, tmpDir, pathMap);
                q.answerImages = rewriteImagePaths(imagesDir, bank.id, q.answerImages, tmpDir, pathMap);
                doneQ++;
                if (listener != null) listener.onProgress(doneQ, totalQ);
            }

            // 打卡记录合并延后到题库解析与图片重写成功之后：本地日期键优先；
            // 缺失或损坏均不阻断题库导入；返回本次并入条数供界面反馈。
            int checkinCount = 0;
            try {
                File checkinFile = new File(tmpDir, CHECKINS_JSON);
                if (checkinFile.exists()) {
                    checkinCount = CheckinStorage.mergeImportJson(context, readFile(checkinFile));
                }
            } catch (Exception ignored) {
                Log.w(TAG, "skip importing checkins.json", ignored);
            }

            writeBankNow(context, bank);
            ImportResult result = new ImportResult();
            result.bank = bank;
            result.checkinCount = Math.max(0, checkinCount);
            return result;
        } finally {
            deleteRecursive(tmpDir);
        }
    }

    // ==================== 内部实现 ====================

    /** 确保存在一个可用的当前题库（含旧数据迁移与兜底新建） */
    private static void ensureBankExists(Context context) {
        if (cacheBank != null) return;

        migrateV3AllBanks(context);   // 老 A（永久毕业）→ S 长期抽查，只跑一次
        migrateV4AllBanks(context);   // 把 v3 批量升 S 的题到期时间错峰打散，只跑一次
        migrateV5AllBanks(context);   // 修正 v4 错峰方向（updateTime=上次复习时间），只跑一次

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
            Log.w(TAG, "legacy questions migration failed", e);
        }
    }

    /**
     * v3 迁移：旧版「A = 永久毕业、不再安排」的题，升级为 S（30 天长期抽查）。
     * 时间戳统一重置为「30 天后到期」——升级当天不涌题，之后按 S=30 天节奏走。
     * 对磁盘上每一个题库文件执行一次，完成后打标记，只跑这一次。
     */
    private static void migrateV3AllBanks(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp.getBoolean(KEY_MIGRATED_V3, false)) return;

        long future = System.currentTimeMillis() + S_INTERVAL_DAYS * 24L * 3600 * 1000;
        File[] files = savesDir(context).listFiles((d, n) -> n.endsWith(".json"));
        if (files != null) {
            for (File f : files) {
                QuestionBank b = readBankFile(f);
                if (b == null || b.questions == null) continue;
                boolean changed = false;
                for (Question q : b.questions) {
                    if ("A".equals(q.status)) {
                        q.status = "S";
                        q.updateTime = future;
                        changed = true;
                    }
                }
                if (changed) writeBankNow(context, b);
            }
        }
        sp.edit().putBoolean(KEY_MIGRATED_V3, true).apply();
    }

    /**
     * v4 迁移：v3 把 64 道 A→S 的题 updateTime 全设成了同一个「30 天后」值，
     * 导致 S 补位机制空转 30 天、到期日又集体撞车。这里把所有 S 题的到期时间
     * 在「昨天 ~ 今天+30 天」内随机打散：少量已逾期的今天就进队列，之后每天自然
     * 到期 2~3 道，由 S 补位（≥1、≤预算 5%）平滑接住。只跑一次。
     */
    private static void migrateV4AllBanks(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp.getBoolean(KEY_MIGRATED_V4, false)) return;

        long now = System.currentTimeMillis();
        long dayMs = 24L * 3600 * 1000;
        java.util.Random rnd = new java.util.Random();
        File[] files = savesDir(context).listFiles((d, n) -> n.endsWith(".json"));
        if (files != null) {
            for (File f : files) {
                QuestionBank b = readBankFile(f);
                if (b == null || b.questions == null) continue;
                boolean changed = false;
                for (Question q : b.questions) {
                    if ("S".equals(q.status)) {
                        int offsetDays = rnd.nextInt(S_INTERVAL_DAYS + 2) - 1; // -1..30
                        long jitter = (long) (rnd.nextDouble() * dayMs);      // 当天内随机时刻
                        q.updateTime = now + offsetDays * dayMs + jitter;
                        changed = true;
                    }
                }
                if (changed) writeBankNow(context, b);
            }
        }
        sp.edit().putBoolean(KEY_MIGRATED_V4, true).apply();
    }

    /**
     * v5 迁移：修正 v4 的错峰方向。updateTime 的语义是「上次复习时间」，
     * isDue 判据是 daysSince(updateTime) >= 30，所以要让 S 题在未来 0~30 天内
     * 滚动到期，应把 updateTime 设为「30 天前 + waitDays」，而不是 v4 误设的「未来时间」。
     * waitDays=0 → 今天到期；waitDays=30 → 30 天后到期。只跑一次。
     */
    private static void migrateV5AllBanks(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp.getBoolean(KEY_MIGRATED_V5, false)) return;

        long now = System.currentTimeMillis();
        long dayMs = 24L * 3600 * 1000;
        java.util.Random rnd = new java.util.Random();
        File[] files = savesDir(context).listFiles((d, n) -> n.endsWith(".json"));
        if (files != null) {
            for (File f : files) {
                QuestionBank b = readBankFile(f);
                if (b == null || b.questions == null) continue;
                boolean changed = false;
                for (Question q : b.questions) {
                    if ("S".equals(q.status)) {
                        int waitDays = rnd.nextInt(S_INTERVAL_DAYS + 1); // 0..30，还有几天到期
                        long jitter = (long) (rnd.nextDouble() * dayMs);
                        q.updateTime = now - S_INTERVAL_DAYS * dayMs + waitDays * dayMs + jitter;
                        changed = true;
                    }
                }
                if (changed) writeBankNow(context, b);
            }
        }
        sp.edit().putBoolean(KEY_MIGRATED_V5, true).apply();
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

    /** 导入命名：优先取压缩包文件名（去掉 .zip 后缀），取不到再用包内 JSON 名，最后兜底「导入题库」 */
    private static String resolveImportBaseName(Context context, Uri uri, String jsonName) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String display = cursor.getString(idx);
                    if (display != null) {
                        display = display.trim();
                        int dot = display.toLowerCase(Locale.ROOT).lastIndexOf(".zip");
                        if (dot > 0) display = display.substring(0, dot).trim();
                        if (!display.isEmpty()) return display;
                    }
                }
            }
        } catch (Exception ignored) {
            // 查询失败时回退到包内 JSON 名
        } finally {
            if (cursor != null) cursor.close();
        }
        if (jsonName != null && !jsonName.trim().isEmpty()) return jsonName.trim();
        return "导入题库";
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
        nq.excluded = q.excluded;
        nq.favorite = q.favorite;
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
            Log.w(TAG, "delete question images failed", e);
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
            Log.w(TAG, "delete private image failed: " + path, e);
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
            Log.e(TAG, "write bank file failed: " + target, e);
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
