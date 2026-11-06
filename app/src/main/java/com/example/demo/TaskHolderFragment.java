package com.example.demo;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.lang.ref.WeakReference;

/**
 * 导入 / 导出任务的 retained 容器。
 * 线程、进度、结果脱离 Activity 实例持有：旋转重建后由新 Activity 重新挂载，
 * 进度框按留存状态重建，已跑完的结果补弹一次；容器不持有 Activity 强引用。
 */
public class TaskHolderFragment extends Fragment {

    public interface Host {
        void showProgress(String title, String message);
        void updateProgress(int done, int total, String message);
        void onExportSuccess();
        void onImportSuccess(QuestionBank bank);
        void onFailure(String message);
    }

    static final int NONE = 0;
    static final int EXPORT = 1;
    static final int IMPORT = 2;

    static final int IDLE = 0;
    static final int RUNNING = 1;
    static final int DONE = 2;
    static final int FAILED = 3;

    int taskType = NONE;
    int state = IDLE;
    int done = 0;
    int total = 0;
    String progressMessage = "";
    QuestionBank importedBank;
    String errorMessage;

    private WeakReference<Host> hostRef = new WeakReference<>(null);

    public TaskHolderFragment() {
        setRetainInstance(true);
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        if (context instanceof Host) {
            hostRef = new WeakReference<>((Host) context);
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        hostRef = new WeakReference<>(null);
    }

    @Nullable
    private Host host() {
        return hostRef.get();
    }

    void startExport(Context appContext, String bankId, Uri uri) {
        taskType = EXPORT;
        state = RUNNING;
        done = 0;
        total = 0;
        progressMessage = "正在准备…";
        errorMessage = null;
        importedBank = null;
        new Thread(() -> {
            try {
                QuestionStorage.exportBankToUri(appContext, bankId, uri, (d, t) -> {
                    done = d;
                    total = t;
                    progressMessage = t > 0 ? "正在写入图片 " + d + " / " + t : "正在写入图片…";
                    notifyProgress();
                });
                state = DONE;
                notifyExportSuccess();
            } catch (Exception e) {
                state = FAILED;
                errorMessage = "导出失败：" + e.getMessage();
                notifyFailure();
            }
        }).start();
    }

    void startImport(Context appContext, Uri uri) {
        taskType = IMPORT;
        state = RUNNING;
        done = 0;
        total = 0;
        progressMessage = "正在解压文件，请稍候…";
        errorMessage = null;
        importedBank = null;
        new Thread(() -> {
            try {
                QuestionBank bank = QuestionStorage.importBank(appContext, uri);
                importedBank = bank;
                state = DONE;
                notifyImportSuccess();
            } catch (Exception e) {
                state = FAILED;
                errorMessage = "导入失败：" + e.getMessage();
                notifyFailure();
            }
        }).start();
    }

    /** Activity 挂载后调用：按留存状态重建进度框，或补弹已完成的结果。 */
    void bindHost(Host host) {
        hostRef = new WeakReference<>(host);
        if (state == RUNNING) {
            Host h = host();
            if (h != null) {
                h.showProgress(titleForTask(), progressMessage);
                h.updateProgress(done, total, progressMessage);
            }
        } else if (state == DONE) {
            Host h = host();
            if (h != null) {
                if (taskType == EXPORT) {
                    h.onExportSuccess();
                } else {
                    h.onImportSuccess(importedBank);
                }
            }
            clearResult();
        } else if (state == FAILED) {
            Host h = host();
            if (h != null) {
                h.onFailure(errorMessage);
            }
            clearResult();
        }
    }

    private String titleForTask() {
        return taskType == IMPORT ? "正在导入题库…" : "正在导出题库…";
    }

    private void clearResult() {
        state = IDLE;
        taskType = NONE;
        errorMessage = null;
        importedBank = null;
    }

    private void notifyProgress() {
        Host h = host();
        if (h != null) h.updateProgress(done, total, progressMessage);
    }

    private void notifyExportSuccess() {
        Host h = host();
        if (h != null) h.onExportSuccess();
    }

    private void notifyImportSuccess() {
        Host h = host();
        if (h != null) h.onImportSuccess(importedBank);
    }

    private void notifyFailure() {
        Host h = host();
        if (h != null) h.onFailure(errorMessage);
    }
}
