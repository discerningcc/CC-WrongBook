package com.example.demo;

import android.app.Activity;
import android.app.Dialog;
import android.widget.Toast;

/**
 * 题目四个动作（重学 / 斩题 / 恢复 / 删除）的统一文案与执行体。
 * 详情页底部按钮与题目列表长按菜单共用同一份口径，避免两处文案漂移。
 * 「修改」不在这里，由调用方直接跳编辑页。
 */
public final class QuestionActions {

    /** 菜单动作类型；EDIT 只用于列表长按菜单派发，没有确认弹窗 */
    public static final int EDIT = 0;
    public static final int RESTUDY = 1;
    public static final int EXCLUDE = 2;
    public static final int RESTORE = 3;
    public static final int DELETE = 4;

    public static final String T_RESTUDY = "重学本题";
    public static final String M_RESTUDY = "确定要重学本题吗？\n将重置为 E 级，重新进入复习计划。";
    public static final String B_RESTUDY = "确认重学";
    public static final String T_RESTORE = "恢复本题";
    public static final String M_RESTORE = "确定恢复本题吗？\n将重置为 E 级，重新进入复习计划。";
    public static final String B_RESTORE = "确认恢复";
    public static final String T_EXCLUDE = "斩题";
    public static final String M_EXCLUDE = "本题将退出复习队列和长期抽查，题目本体保留，可在题目列表恢复。\n确定斩题吗？";
    public static final String B_EXCLUDE = "确认斩题";
    public static final String T_DELETE = "删除本题";
    public static final String M_DELETE = "确定要删除本题吗？\n本题引用的图片将一并清理，删除后无法恢复！";
    public static final String B_DELETE = "删除";

    public static final String TOAST_RESTUDY = "已重置，将重新复习";
    public static final String TOAST_RESTORE = "已恢复，重新开始复习";
    public static final String TOAST_EXCLUDE = "已斩题，不再进入复习";
    public static final String TOAST_DELETE = "题目已删除";

    private QuestionActions() {
    }

    /**
     * 长按菜单项文案：普通题「修改 / 重学 / 斩题 / 删除」；
     * 已斩题与详情页一致，隐藏没意义的「重学」，只留「修改 / 恢复 / 删除」。
     */
    public static String[] menuLabels(Question q) {
        if (q != null && q.excluded) {
            return new String[]{"修改", "恢复", "删除"};
        }
        return new String[]{"修改", "重学", "斩题", "删除"};
    }

    /** 菜单里需要红字（危险）的那一项，永远是最后一项「删除」 */
    public static int dangerIndexOf(Question q) {
        return menuLabels(q).length - 1;
    }

    /** 长按菜单下标 → 动作类型，与 {@link #menuLabels(Question)} 一一对应 */
    public static int kindAt(Question q, int index) {
        boolean excluded = q != null && q.excluded;
        if (index == 0) return EDIT;
        if (excluded) return index == 1 ? RESTORE : DELETE;
        switch (index) {
            case 1: return RESTUDY;
            case 2: return EXCLUDE;
            default: return DELETE;
        }
    }

    /** 弹对应确认框，确认后执行并回调 onDone；无需弹窗的动作返回 null */
    public static Dialog confirm(Activity activity, int kind, Question q, Runnable onDone) {
        if (activity == null || q == null) return null;
        switch (kind) {
            case RESTUDY:
                return AppDialog.confirm(activity, T_RESTUDY, M_RESTUDY, B_RESTUDY, false,
                        () -> run(onDone, () -> execRestudy(activity, q)));
            case RESTORE:
                return AppDialog.confirm(activity, T_RESTORE, M_RESTORE, B_RESTORE, false,
                        () -> run(onDone, () -> execRestore(activity, q)));
            case EXCLUDE:
                return AppDialog.confirm(activity, T_EXCLUDE, M_EXCLUDE, B_EXCLUDE, false,
                        () -> run(onDone, () -> execExclude(activity, q)));
            case DELETE:
                return AppDialog.confirm(activity, T_DELETE, M_DELETE, B_DELETE, true,
                        () -> run(onDone, () -> execDelete(activity, q)));
            default:
                return null;
        }
    }

    private static void run(Runnable onDone, Runnable action) {
        action.run();
        if (onDone != null) onDone.run();
    }

    /** 重学：打回 E 级、刷新更新时间，斩题标记不动 */
    public static void execRestudy(Activity activity, Question q) {
        QuestionStorage.restudyQuestion(activity, q);
        Toast.makeText(activity, TOAST_RESTUDY, Toast.LENGTH_SHORT).show();
    }

    /** 恢复：取消斩题标记并打回 E 级 */
    public static void execRestore(Activity activity, Question q) {
        QuestionStorage.restoreQuestion(activity, q);
        Toast.makeText(activity, TOAST_RESTORE, Toast.LENGTH_SHORT).show();
    }

    /** 斩题：软标记退出复习队列，题目本体保留 */
    public static void execExclude(Activity activity, Question q) {
        QuestionStorage.excludeQuestion(activity, q);
        Toast.makeText(activity, TOAST_EXCLUDE, Toast.LENGTH_SHORT).show();
    }

    /** 删除：连同题目引用的图片一并清理 */
    public static void execDelete(Activity activity, Question q) {
        QuestionStorage.delete(activity, q);
        Toast.makeText(activity, TOAST_DELETE, Toast.LENGTH_SHORT).show();
    }
}
