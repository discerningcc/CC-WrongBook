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
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 打卡存储：checkins/ 目录下一份 checkins.json，Map&lt;dateKey, 记录&gt;，一天一条。
 * 写入先 .tmp 再改名；文件损坏按空集合处理，不影响题库与会话记录。
 * 资格判定、连续天数等均为纯静态函数，可在宿主机单测，不触碰 Context 与文件系统。
 */
public class CheckinStorage {

    private static final String TAG = "CheckinStorage";
    private static final String DIR_NAME = "checkins";
    private static final String FILE_NAME = "checkins.json";
    private static final long ONE_DAY = 24L * 60 * 60 * 1000;

    private static final SimpleDateFormat KEY_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);

    // ==================== 纯函数：资格判定 ====================

    /**
     * 今日是否可打卡。
     * 门禁：自然日模式开启、打卡总开关开启、题库非空、今日尚未打卡；
     * 有限量：done >= budget；不限量（budget<=0）：今日无待复习（dueCount<=0）视为达成。
     */
    public static boolean isEligible(boolean naturalDayOn, boolean checkinEnabled, boolean bankEmpty,
                                     int budget, int done, int dueCount, boolean alreadyChecked) {
        if (!naturalDayOn || !checkinEnabled || bankEmpty || alreadyChecked) return false;
        if (budget > 0) return done >= budget;
        return dueCount <= 0;
    }

    // ==================== 纯函数：统计 ====================

    /** 连续打卡天数：今天已打则从今天往前数，否则从昨天往前数；断档即止。 */
    public static int streakDays(Set<String> checkedKeys, long todayDayStart) {
        if (checkedKeys == null || checkedKeys.isEmpty()) return 0;
        long day = SessionStorage.dayStart(todayDayStart);
        String todayKey = dateKeyOf(day);
        if (!checkedKeys.contains(todayKey)) {
            day -= ONE_DAY;
        }
        int n = 0;
        while (checkedKeys.contains(dateKeyOf(day))) {
            n++;
            day -= ONE_DAY;
        }
        return n;
    }

    /** 累计打卡天数。 */
    public static int totalCount(Set<String> checkedKeys) {
        return checkedKeys == null ? 0 : checkedKeys.size();
    }

    /** 指定自然月内的打卡天数（year 四位、month 1~12）。 */
    public static int monthCount(Set<String> checkedKeys, int year, int month) {
        if (checkedKeys == null) return 0;
        int n = 0;
        String prefix = String.format(Locale.CHINA, "%04d-%02d", year, month);
        for (String key : checkedKeys) {
            if (key != null && key.startsWith(prefix)) n++;
        }
        return n;
    }

    // ==================== 日期工具 ====================

    /** 时间戳 → yyyy-MM-dd（本地时区，0 点对齐）。 */
    public static String dateKeyOf(long ts) {
        return KEY_FORMAT.format(new Date(SessionStorage.dayStart(ts)));
    }

    /** 今天的 dateKey。 */
    public static String todayKey() {
        return dateKeyOf(System.currentTimeMillis());
    }

    // ==================== 读写 ====================

    /** 全部打卡记录，按打卡时间倒序。 */
    public static List<CheckinRecord> loadAll(Context context) {
        Map<String, CheckinRecord> map = loadMap(context);
        List<CheckinRecord> list = new ArrayList<>(map.values());
        Collections.sort(list, new Comparator<CheckinRecord>() {
            @Override
            public int compare(CheckinRecord a, CheckinRecord b) {
                return Long.compare(b.dayStart, a.dayStart);
            }
        });
        return list;
    }

    /** 全部打卡日期键集合（供统计纯函数使用）。 */
    public static Set<String> loadKeySet(Context context) {
        return loadMap(context).keySet();
    }

    public static CheckinRecord get(Context context, String dateKey) {
        if (dateKey == null) return null;
        return loadMap(context).get(dateKey);
    }

    public static boolean isCheckedToday(Context context) {
        return get(context, todayKey()) != null;
    }

    /** 记录今日打卡；重复调用同一天覆盖为最新一次快照。 */
    public static void checkIn(Context context, int done, int budget, String bankName) {
        Map<String, CheckinRecord> map = loadMap(context);
        long now = System.currentTimeMillis();
        CheckinRecord rec = new CheckinRecord();
        rec.dateKey = dateKeyOf(now);
        rec.dayStart = SessionStorage.dayStart(now);
        rec.done = done;
        rec.budget = budget;
        rec.checkedAt = now;
        rec.bankName = bankName;
        map.put(rec.dateKey, rec);
        writeAtomic(context, new Gson().toJson(map));
    }

    // ==================== 备份导出 / 导入 ====================

    /** 导出包内 checkins.json 的原始文本；无记录返回空串。 */
    public static String exportJson(Context context) {
        Map<String, CheckinRecord> map = loadMap(context);
        if (map.isEmpty()) return "";
        return new Gson().toJson(map);
    }

    /**
     * 合并导入备份里的打卡记录：日期键已存在时本地优先，不覆盖；其余并入。
     * 入参为空或解析失败时静默跳过，返回本次并入条数（0 表示未并入）。
     */
    public static int mergeImportJson(Context context, String json) {
        if (json == null || json.trim().isEmpty()) return 0;
        Map<String, CheckinRecord> local = loadMap(context);
        Map<String, CheckinRecord> incoming;
        try {
            Type type = new TypeToken<Map<String, CheckinRecord>>() {}.getType();
            incoming = new Gson().fromJson(json, type);
        } catch (Exception e) {
            Log.w(TAG, "skip corrupt checkins json", e);
            return 0;
        }
        if (incoming == null || incoming.isEmpty()) return 0;
        int added = mergeMaps(local, incoming);
        if (added > 0) writeAtomic(context, new Gson().toJson(local));
        return added;
    }

    /**
     * 纯函数：把 incoming 中合法且本地缺失的记录按日期键并入 local，返回并入条数。
     * 本地已有日期不覆盖；非法键/记录跳过。不触碰 Context 与文件系统，可宿主机单测。
     */
    public static int mergeMaps(Map<String, CheckinRecord> local, Map<String, CheckinRecord> incoming) {
        if (local == null || incoming == null) return 0;
        int added = 0;
        for (Map.Entry<String, CheckinRecord> e : incoming.entrySet()) {
            String key = e.getKey();
            CheckinRecord rec = e.getValue();
            if (!isValidRecord(key, rec)) continue;
            if (!local.containsKey(key)) {
                local.put(key, rec);
                added++;
            }
        }
        return added;
    }

    /** 记录合法性：日期键为合法 yyyy-MM-dd，时间/题数/预算非负。 */
    static boolean isValidRecord(String key, CheckinRecord rec) {
        if (key == null || rec == null) return false;
        if (!isValidDateKey(key)) return false;
        return rec.dayStart >= 0 && rec.checkedAt >= 0 && rec.done >= 0 && rec.budget >= 0;
    }

    private static boolean isValidDateKey(String key) {
        if (!key.matches("\\d{4}-\\d{2}-\\d{2}")) return false;
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
        fmt.setLenient(false);
        try {
            fmt.parse(key);
            return true;
        } catch (ParseException e) {
            return false;
        }
    }

    // ==================== 内部实现 ====================

    private static Map<String, CheckinRecord> loadMap(Context context) {
        File f = fileOf(context);
        if (!f.exists()) return new LinkedHashMap<>();
        try {
            Type type = new TypeToken<Map<String, CheckinRecord>>() {}.getType();
            Map<String, CheckinRecord> map = new Gson().fromJson(readFile(f), type);
            return map == null ? new LinkedHashMap<>() : new LinkedHashMap<>(map);
        } catch (Exception e) {
            Log.w(TAG, "skip corrupt checkin file: " + f.getName(), e);
            return new LinkedHashMap<>();
        }
    }

    private static File dir(Context context) {
        return context.getDir(DIR_NAME, Context.MODE_PRIVATE);
    }

    private static File fileOf(Context context) {
        return new File(dir(context), FILE_NAME);
    }

    private static void writeAtomic(Context context, String content) {
        File target = fileOf(context);
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try {
            FileOutputStream fos = new FileOutputStream(tmp);
            try {
                fos.write(content.getBytes("UTF-8"));
            } finally {
                fos.close();
            }
            if (!tmp.renameTo(target)) {
                if (target.exists()) target.delete();
                if (!tmp.renameTo(target)) throw new IOException("rename failed: " + target);
            }
        } catch (IOException e) {
            tmp.delete();
            Log.e(TAG, "write checkin file failed: " + target, e);
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
