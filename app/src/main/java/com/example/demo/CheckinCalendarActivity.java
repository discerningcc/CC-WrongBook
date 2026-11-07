package com.example.demo;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 打卡日历：月视图展示已打卡日期，顶部连续/累计/本月汇总。
 * 24 小时模式或打卡总开关关闭时为只读态，不可新打卡，历史记录仍可查看。
 */
public class CheckinCalendarActivity extends AppCompatActivity {

    private static final String KEY_YEAR = "checkin_year";
    private static final String KEY_MONTH = "checkin_month";
    private static final String[] WEEKDAYS = {"日", "一", "二", "三", "四", "五", "六"};

    private TextView monthTitle;
    private TextView summaryView;
    private TextView bannerView;
    private LinearLayout weekdayHeader;
    private LinearLayout grid;

    private int year;
    private int month; // 0-based
    private final Map<String, CheckinRecord> records = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_checkin_calendar);

        monthTitle = findViewById(R.id.tv_month_title);
        summaryView = findViewById(R.id.tv_checkin_summary);
        bannerView = findViewById(R.id.tv_checkin_banner);
        weekdayHeader = findViewById(R.id.weekday_header);
        grid = findViewById(R.id.calendar_grid);

        findViewById(R.id.btn_checkin_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_prev_month).setOnClickListener(v -> shiftMonth(-1));
        findViewById(R.id.btn_next_month).setOnClickListener(v -> shiftMonth(1));

        Calendar now = Calendar.getInstance();
        year = now.get(Calendar.YEAR);
        month = now.get(Calendar.MONTH);
        if (savedInstanceState != null) {
            year = savedInstanceState.getInt(KEY_YEAR, year);
            month = savedInstanceState.getInt(KEY_MONTH, month);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadRecords();
        render();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_YEAR, year);
        outState.putInt(KEY_MONTH, month);
    }

    private void reloadRecords() {
        records.clear();
        List<CheckinRecord> list = CheckinStorage.loadAll(this);
        for (CheckinRecord r : list) {
            if (r != null && r.dateKey != null) records.put(r.dateKey, r);
        }
    }

    private void shiftMonth(int delta) {
        month += delta;
        if (month < 0) {
            month = 11;
            year--;
        } else if (month > 11) {
            month = 0;
            year++;
        }
        render();
    }

    private void render() {
        Calendar now = Calendar.getInstance();
        int streak = CheckinStorage.streakDays(records.keySet(), now.getTimeInMillis());
        int total = CheckinStorage.totalCount(records.keySet());
        int monthCount = CheckinStorage.monthCount(records.keySet(),
                now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1);
        summaryView.setText("连续 " + streak + " 天 · 累计 " + total + " 天 · 本月 " + monthCount + " 天");

        boolean gateOpen = QuestionStorage.isNaturalDayEnabled(this)
                && QuestionStorage.isCheckinEnabled(this);
        bannerView.setVisibility(gateOpen ? View.GONE : View.VISIBLE);
        if (!gateOpen) {
            bannerView.setText("当前为 24 小时模式或打卡已关闭，仅可查看历史记录");
        }

        monthTitle.setText(year + "年" + (month + 1) + "月");
        buildWeekdayHeader();
        buildGrid(now);
    }

    private void buildWeekdayHeader() {
        weekdayHeader.removeAllViews();
        for (String label : WEEKDAYS) {
            TextView tv = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(32), 1f);
            tv.setLayoutParams(lp);
            tv.setGravity(Gravity.CENTER);
            tv.setText(label);
            tv.setTextSize(12);
            tv.setTextColor(getColor(R.color.home_text_secondary));
            weekdayHeader.addView(tv);
        }
    }

    private void buildGrid(Calendar today) {
        grid.removeAllViews();
        Calendar first = Calendar.getInstance();
        first.clear();
        first.set(year, month, 1);
        int firstWeekday = first.get(Calendar.DAY_OF_WEEK); // 周日=1
        int daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH);

        LinearLayout row = newRow();
        int inRow = 0;
        for (int i = 1; i < firstWeekday; i++) {
            addBlank(row);
            inRow++;
        }
        for (int day = 1; day <= daysInMonth; day++) {
            if (inRow == 7) {
                grid.addView(row);
                row = newRow();
                inRow = 0;
            }
            String key = dateKeyOf(year, month, day);
            CheckinRecord rec = records.get(key);
            boolean isToday = sameDay(today, year, month, day);
            boolean future = isAfter(today, year, month, day);
            addCell(row, day, key, rec, isToday, future);
            inRow++;
        }
        while (inRow < 7) {
            addBlank(row);
            inRow++;
        }
        grid.addView(row);
    }

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private void addBlank(LinearLayout row) {
        TextView tv = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        tv.setLayoutParams(lp);
        row.addView(tv);
    }

    private void addCell(LinearLayout row, int day, String key, CheckinRecord rec,
                         boolean isToday, boolean future) {
        TextView tv = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        tv.setLayoutParams(lp);
        tv.setGravity(Gravity.CENTER);
        tv.setText(String.valueOf(day));
        tv.setTextSize(14);

        if (rec != null) {
            tv.setBackgroundResource(R.drawable.bg_checkin_checked);
            tv.setTextColor(0xFFFFFFFF);
            tv.setOnClickListener(v -> showDetail(rec));
        } else if (isToday) {
            tv.setBackgroundResource(R.drawable.bg_checkin_today);
            tv.setTextColor(getColor(R.color.detail_primary));
            tv.setOnClickListener(v -> Toast.makeText(this, "今日尚未打卡，可回主页打卡", Toast.LENGTH_SHORT).show());
        } else if (future) {
            tv.setTextColor(0xFFBDBDBD);
        } else {
            tv.setTextColor(getColor(R.color.home_text_secondary));
        }
        row.addView(tv);
    }

    private void showDetail(CheckinRecord rec) {
        SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy年M月d日", Locale.CHINA);
        String dateText = dateFmt.format(new java.util.Date(rec.dayStart));
        String goalText = rec.budget > 0
                ? "完成 " + rec.done + " / 目标 " + rec.budget + " 道"
                : "不限量 · 完成 " + rec.done + " 道";
        String timeText = SessionStorage.formatTime(rec.checkedAt);
        String bankText = rec.bankName == null || rec.bankName.isEmpty() ? "—" : rec.bankName;

        new AlertDialog.Builder(this)
                .setTitle(dateText)
                .setMessage(goalText + "\n打卡时间：" + timeText + "\n题库：" + bankText)
                .setPositiveButton("关闭", null)
                .show();
    }

    private static String dateKeyOf(int y, int m, int d) {
        return String.format(Locale.CHINA, "%04d-%02d-%02d", y, m + 1, d);
    }

    private static boolean sameDay(Calendar today, int y, int m, int d) {
        return today.get(Calendar.YEAR) == y
                && today.get(Calendar.MONTH) == m
                && today.get(Calendar.DAY_OF_MONTH) == d;
    }

    private static boolean isAfter(Calendar today, int y, int m, int d) {
        if (today.get(Calendar.YEAR) != y) return today.get(Calendar.YEAR) < y;
        if (today.get(Calendar.MONTH) != m) return today.get(Calendar.MONTH) < m;
        return today.get(Calendar.DAY_OF_MONTH) < d;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
