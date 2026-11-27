package com.example.demo;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 复习会话存储：review_history/ 目录下一条会话一个 JSON 文件（<会话id>.json）。
 * 一条一文件的好处：答题时只重写当前这一条（约 20 KB），历史记录一个字节都不碰；
 * 写入先写 .tmp 再改名，崩在中间旧文件仍完好。
 * 每个题库最多保留 MAX_SESSIONS_PER_BANK 条已归档记录，未完成（ACTIVE）的会话不会被裁掉。
 * 旧版把所有会话放在 sessions.json 一个文件里，首次启动会自动拆成一条一文件并留下 .bak 备份。
 */
public class SessionStorage {

    private static final String TAG = "SessionStorage";
    private static final String DIR_NAME = "review_history";
    private static final String LEGACY_FILE = "sessions.json";
    private static final String LEGACY_BACKUP = "sessions.json.bak";
    private static final int MAX_SESSIONS_PER_BANK = 100;
    private static final int SNAPSHOT_LEN = 60;

    /** 写队列：会话 id → 该会话最新一份 JSON；同一会话多次保存只留最后一份，不堆叠 */
    private static final Map<String, PendingWrite> pendingWrites = new LinkedHashMap<>();
    private static final Object WRITE_LOCK = new Object();
    private static boolean writeRunning = false;
    private static int flushSeq = 0;

    /** 写队列里的 flush 标记项 key：真实会话 id 都是 yyyyMMdd_HHmmss_xxx，不会撞上 */
    private static final String FLUSH_KEY = "__flush";

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "session-writer");
        t.setPriority(Thread.MIN_PRIORITY + 1);   // 别和界面抢 CPU
        return t;
    });

    /** 一次待落盘的会话快照：json 已在调用线程序列化好，write 线程只管写盘 */
    private static final class PendingWrite {
        final String id;
        final String json;
        final boolean trim;       // 会话已结束，写完顺手裁剪该题库的超量记录
        final String bankId;
        final CountDownLatch latch;   // 仅 flush 标记项使用：写到这里代表前面的都落盘了

        PendingWrite(String id, String json, boolean trim, String bankId) {
            this(id, json, trim, bankId, null);
        }

        PendingWrite(String id, String json, boolean trim, String bankId, CountDownLatch latch) {
            this.id = id;
            this.json = json;
            this.trim = trim;
            this.bankId = bankId;
            this.latch = latch;
        }
    }

    // ==================== 读写 ====================

    public static List<ReviewSession> loadAll(Context context) {
        migrateLegacy(context);
        List<ReviewSession> list = new ArrayList<>();
        File[] files = dir(context).listFiles();
        if (files == null) return list;
        Gson gson = new Gson();
        for (File f : files) {
            if (!f.getName().endsWith(".json")) continue;
            if (LEGACY_FILE.equals(f.getName())) continue;   // 旧版单文件不当成一条会话
            try {
                ReviewSession s = gson.fromJson(readFile(f), ReviewSession.class);
                if (s == null || s.id == null) continue;
                if (s.items == null) s.items = new ArrayList<>();
                list.add(s);
            } catch (Exception e) {
                // 单个文件坏了只丢这一条，不影响其他记录
                Log.w(TAG, "skip corrupt session file: " + f.getName(), e);
            }
        }
        return list;
    }

    /** 保存单个会话：只写它自己的那个文件（序列化在当前线程，写盘交给后台队列） */
    public static void save(Context context, ReviewSession session) {
        if (session == null || session.id == null) return;
        migrateLegacy(context);
        Context app = appOf(context);
        // 结束（归档/完成）时才可能超出上限，标记写完顺手裁剪一次
        boolean trim = !ReviewSession.STATUS_ACTIVE.equals(session.status);
        requestWrite(app, session.id, new Gson().toJson(session), trim, session.bankId);
    }

    public static void remove(Context context, String sessionId) {
        if (sessionId == null) return;
        flush(context);                       // 先把队列排空，否则删完又会被旧快照写回
        synchronized (WRITE_LOCK) {
            pendingWrites.remove(safeId(sessionId));
        }
        File f = fileOf(context, sessionId);
        if (f.exists() && !f.delete()) {
            f.deleteOnExit();
        }
    }

    /** 记录文件是否仍在磁盘上：用于发现外部删除后，避免把内存里的会话副本又写回复活 */
    public static boolean exists(Context context, String sessionId) {
        if (sessionId == null) return false;
        synchronized (WRITE_LOCK) {
            if (pendingWrites.containsKey(safeId(sessionId))) return true;   // 还在队列里也算存在
        }
        return fileOf(context, sessionId).exists();
    }

    // ==================== 查询 ====================

    /** 指定题库下未完成的会话（最近一次） */
    public static ReviewSession getActive(Context context, String bankId) {
        ReviewSession latest = null;
        for (ReviewSession s : loadAll(context)) {
            if (!ReviewSession.STATUS_ACTIVE.equals(s.status)) continue;
            if (bankId != null && !bankId.equals(s.bankId)) continue;
            if (latest == null || s.startTime > latest.startTime) latest = s;
        }
        return latest;
    }

    /** 指定题库下最近一次会话（不限状态），bankId 传 null 表示全部题库 */
    public static ReviewSession getLatest(Context context, String bankId) {
        ReviewSession latest = null;
        for (ReviewSession s : loadAll(context)) {
            if (bankId != null && !bankId.equals(s.bankId)) continue;
            if (latest == null || s.startTime > latest.startTime) latest = s;
        }
        return latest;
    }

    // ==================== 创建与归档 ====================

    /** 用当前待复习题目生成一个新会话，并把题目顺序固定下来 */
    public static ReviewSession create(Context context, String bankId, String bankName,
                                       List<Question> questions) {
        ReviewSession s = new ReviewSession();
        s.id = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date())
                + "_" + (int) (Math.random() * 900 + 100);
        s.bankId = bankId;
        s.bankName = bankName;
        s.startTime = System.currentTimeMillis();
        s.endTime = 0;
        s.currentIndex = 0;
        s.answerShown = false;
        s.status = ReviewSession.STATUS_ACTIVE;
        s.items = new ArrayList<>();
        if (questions != null) {
            for (Question q : questions) {
                ReviewSession.Item it = new ReviewSession.Item();
                it.questionId = q.id;
                it.contentSnapshot = snapshot(q.content);
                it.hadImages = q.contentImages != null && !q.contentImages.isEmpty();
                s.items.add(it);
            }
        }
        s.originalCount = s.items.size();
        save(context, s);   // 新会话只写它自己的文件
        return s;
    }

    /** 归档会话：补结束时间后落盘 */
    public static void archive(Context context, ReviewSession s, String status) {
        if (s == null) return;
        s.status = status;
        if (s.endTime <= 0) {
            long last = s.lastAnsweredAt();
            s.endTime = last > 0 ? last : System.currentTimeMillis();
        }
        save(context, s);
    }

    // ==================== 工具方法 ====================

    /** 两个时间是否在同一个自然日 */
    public static boolean isSameDay(long a, long b) {
        return dayStart(a) == dayStart(b);
    }

    /** 某个时间戳所在自然日的 0 点 */
    public static long dayStart(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** 时分，如 14:20 */
    public static String formatTime(long ts) {
        return new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(ts));
    }

    /** 时长格式化：不足 1 小时 mm:ss，超 1 小时 h:mm:ss；负数按 0 处理 */
    public static String formatDuration(long ms) {
        long sec = Math.max(0, ms / 1000);
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        if (h > 0) return h + ":" + String.format("%02d:%02d", m, s);
        return String.format("%02d:%02d", m, s);
    }

    /** 日期分组标题：今天 / 昨天 / 11月3日 / 2025年11月3日 */
    public static String formatDateHeader(long ts) {
        long today = dayStart(System.currentTimeMillis());
        long day = dayStart(ts);
        long oneDay = 24L * 60 * 60 * 1000;
        if (day == today) return "今天";
        if (day == today - oneDay) return "昨天";
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        String md = (c.get(Calendar.MONTH) + 1) + "月" + c.get(Calendar.DAY_OF_MONTH) + "日";
        if (c.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR)) return md;
        return c.get(Calendar.YEAR) + "年" + md;
    }

    /** 题目内容快照：压成单行并截断 */
    public static String snapshot(String content) {
        if (content == null) return "";
        String oneLine = content.replace("\n", " ").replace("\r", " ").trim();
        if (oneLine.length() <= SNAPSHOT_LEN) return oneLine;
        return oneLine.substring(0, SNAPSHOT_LEN) + "…";
    }

    // ==================== 内部实现 ====================

    /**
     * 裁剪：只针对某一个题库，保留最近 MAX_SESSIONS_PER_BANK 条已结束会话，多余的删文件。
     * ACTIVE（正在做的那一轮）永远不参与裁剪，也不会被删。
     * 只在会话结束时调用一次，答题过程中不会触发。
     */
    private static void trimBank(Context context, String bankId) {
        List<ReviewSession> archived = new ArrayList<>();
        for (ReviewSession s : loadAll(context)) {
            if (ReviewSession.STATUS_ACTIVE.equals(s.status)) continue;
            boolean sameBank = bankId == null ? s.bankId == null : bankId.equals(s.bankId);
            if (sameBank) archived.add(s);
        }
        if (archived.size() <= MAX_SESSIONS_PER_BANK) return;
        Collections.sort(archived, new Comparator<ReviewSession>() {
            @Override
            public int compare(ReviewSession a, ReviewSession b) {
                return Long.compare(b.startTime, a.startTime);
            }
        });
        for (int i = MAX_SESSIONS_PER_BANK; i < archived.size(); i++) {
            File f = fileOf(context, archived.get(i).id);
            if (f.exists() && !f.delete()) f.deleteOnExit();
        }
    }

    /** 旧版单文件 sessions.json → 拆成一条一个文件，原文件改名成 sessions.json.bak 留底 */
    private static void migrateLegacy(Context context) {
        File legacy = new File(dir(context), LEGACY_FILE);
        if (!legacy.exists()) return;
        try {
            Type type = new TypeToken<List<ReviewSession>>() {}.getType();
            List<ReviewSession> list = new Gson().fromJson(readFile(legacy), type);
            if (list != null) {
                Gson gson = new Gson();
                for (ReviewSession s : list) {
                    if (s == null || s.id == null) continue;
                    File target = fileOf(context, s.id);
                    if (target.exists()) continue;   // 已经拆出来了就跳过
                    writeAtomic(target, gson.toJson(s));
                }
            }
        } catch (Exception e) {
            // 迁移中途出错：保留 sessions.json 不动，下次启动再试，避免两头都丢
            Log.w(TAG, "legacy session migration failed, retry next launch", e);
            return;
        }
        File bak = new File(dir(context), LEGACY_BACKUP);
        if (bak.exists() && !bak.delete()) {
            // 旧的备份删不掉也不影响，改名时会被覆盖
        }
        if (!legacy.renameTo(bak)) {
            // 改名失败（极少见）：原文件保留，loadAll 会跳过它，不影响已拆出的记录
        }
    }

    /** 会话 id 里的非法字符统一替换成下划线，保证文件名安全 */
    private static String safeId(String id) {
        return id.replaceAll("[^A-Za-z0-9_\\-]", "_");
    }

    private static Context appOf(Context context) {
        Context app = context.getApplicationContext();
        return app != null ? app : context;
    }

    // ==================== 后台写队列 ====================

    /** 只把最新一份快照塞进队列并唤醒写线程：写线程忙时不会叠加多个任务 */
    private static void requestWrite(Context app, String sessionId, String json, boolean trim, String bankId) {
        String id = safeId(sessionId);
        synchronized (WRITE_LOCK) {
            pendingWrites.put(id, new PendingWrite(id, json, trim, bankId));
            if (writeRunning) return;
            writeRunning = true;
        }
        WRITER.execute(() -> drainWrites(app));
    }

    /** 写线程主体：一直取队列里最早的一条写盘，直到排空才退出 */
    private static void drainWrites(Context app) {
        while (true) {
            PendingWrite w;
            synchronized (WRITE_LOCK) {
                if (pendingWrites.isEmpty()) {
                    writeRunning = false;
                    return;
                }
                String key = pendingWrites.keySet().iterator().next();
                w = pendingWrites.remove(key);
            }
            if (w.latch != null) {   // flush 标记：它前面排的都已经写完了
                w.latch.countDown();
                continue;
            }
            writeAtomic(fileOf(app, w.id), w.json);
            if (w.trim) trimBank(app, w.bankId);
        }
    }

    /** 把队列里已排好的写入全部等到落盘（离开复习页时调用，最多等 3 秒） */
    public static void flush(Context context) {
        CountDownLatch latch = new CountDownLatch(1);
        boolean startWriter;
        synchronized (WRITE_LOCK) {
            String key = FLUSH_KEY + "_" + (flushSeq++);   // 每次一个独立标记，多个 flush 互不覆盖
            pendingWrites.put(key, new PendingWrite(key, null, false, null, latch));
            startWriter = !writeRunning;
            if (startWriter) writeRunning = true;
        }
        if (startWriter) WRITER.execute(() -> drainWrites(appOf(context)));
        awaitQuietly(latch, 3);
    }

    private static void awaitQuietly(CountDownLatch latch, long seconds) {
        try {
            latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static File dir(Context context) {
        return context.getDir(DIR_NAME, Context.MODE_PRIVATE);
    }

    private static File fileOf(Context context, String id) {
        return new File(dir(context), safeId(id) + ".json");
    }

    /** 先写 .tmp 再改名：写到一半被打断，原文件仍然完整可用 */
    private static void writeAtomic(File target, String content) {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try {
            FileOutputStream fos = new FileOutputStream(tmp);
            try {
                fos.write(content.getBytes("UTF-8"));
            } finally {
                fos.close();
            }
            if (!tmp.renameTo(target)) {
                // 某些文件系统不允许直接覆盖改名，退一步：删掉旧的再改名
                if (target.exists()) target.delete();
                if (!tmp.renameTo(target)) throw new IOException("rename failed: " + target);
            }
        } catch (IOException e) {
            tmp.delete();
            Log.e(TAG, "write session file failed: " + target, e);
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
}
