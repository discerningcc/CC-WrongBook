package com.example.demo;

import android.app.Activity;
import android.content.Context;
import android.view.WindowManager;

/**
 * 做题页面保持屏幕常亮：给当前窗口加 {@link WindowManager.LayoutParams#FLAG_KEEP_SCREEN_ON}。
 * 该标记只在本窗口可见期间生效，页面退到后台/退出后屏幕照常按系统策略息屏，
 * 不需要 WakeLock、不申请权限，也就不会出现忘记释放导致的耗电。
 */
public final class ScreenOn {

    /** 与设置页共用同一份 SharedPreferences */
    private static final String SP_SETTINGS = "settings";

    private ScreenOn() {
    }

    /** 设置项「做题时屏幕常亮」，默认开 */
    public static boolean isEnabled(Context context) {
        return context.getSharedPreferences(SP_SETTINGS, Context.MODE_PRIVATE)
                .getBoolean(SettingsActivity.KEY_SCREEN_ON, true);
    }

    /**
     * 按设置给窗口加上或清掉常亮标记。
     * 需要在页面每次回到前台时调用：旋转重建会换窗口，只写在 onCreate 里会漏。
     */
    public static void apply(Activity activity) {
        if (isEnabled(activity)) {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }
}
