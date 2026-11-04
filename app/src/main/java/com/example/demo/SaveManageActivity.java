package com.example.demo;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 题库管理页：
 * 点击题库 = 切换当前题库；长按 = 重命名 / 导出 / 删除；
 * 顶部按钮 = 新建 / 导入 / 导出当前。
 */
public class SaveManageActivity extends AppCompatActivity {

    private static final int REQ_IMPORT = 2001;
    private static final int REQ_EXPORT = 2002;

    private RecyclerView recycler;
    private BankAdapter adapter;
    private String pendingExportBankId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_save_manage);

        recycler = findViewById(R.id.recycler_banks);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new BankAdapter();
        recycler.setAdapter(adapter);

        findViewById(R.id.btn_new_bank).setOnClickListener(v -> showNewBankDialog());
        findViewById(R.id.btn_import_bank).setOnClickListener(v -> openImport());
        findViewById(R.id.btn_export_bank).setOnClickListener(v -> {
            QuestionStorage.BankInfo current = findCurrent();
            if (current == null) {
                Toast.makeText(this, "没有可导出的题库", Toast.LENGTH_SHORT).show();
            } else {
                exportBank(current);
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        QuestionStorage.flush(this);   // 改名/新建的改动在离开页面前落盘
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadBanks();
    }

    private void loadBanks() {
        adapter.updateList(QuestionStorage.listBanks(this));
    }

    private QuestionStorage.BankInfo findCurrent() {
        for (QuestionStorage.BankInfo info : QuestionStorage.listBanks(this)) {
            if (info.current) return info;
        }
        return null;
    }

    private void showNewBankDialog() {
        EditText et = new EditText(this);
        et.setHint("题库名称，如：数学");
        new AlertDialog.Builder(this)
                .setTitle("新建题库")
                .setView(et)
                .setPositiveButton("创建", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    QuestionStorage.createBank(this, name);
                    Toast.makeText(this, "已创建并切换到：" + name, Toast.LENGTH_SHORT).show();
                    loadBanks();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void openImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQ_IMPORT);
    }

    private void exportBank(QuestionStorage.BankInfo info) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, info.name + ".zip");
        pendingExportBankId = info.id;
        startActivityForResult(intent, REQ_EXPORT);
    }

    private void showBankActions(QuestionStorage.BankInfo info) {
        String[] actions = {"切换到此题库", "重命名", "导出", "删除"};
        new AlertDialog.Builder(this)
                .setTitle(info.name)
                .setItems(actions, (d, which) -> {
                    switch (which) {
                        case 0:
                            QuestionStorage.switchBank(this, info.id);
                            Toast.makeText(this, "已切换到：" + info.name, Toast.LENGTH_SHORT).show();
                            finish();
                            break;
                        case 1:
                            showRenameDialog(info);
                            break;
                        case 2:
                            exportBank(info);
                            break;
                        case 3:
                            showDeleteDialog(info);
                            break;
                    }
                })
                .show();
    }

    private void showRenameDialog(QuestionStorage.BankInfo info) {
        EditText et = new EditText(this);
        et.setText(info.name);
        et.setSelection(info.name.length());
        new AlertDialog.Builder(this)
                .setTitle("重命名题库")
                .setView(et)
                .setPositiveButton("确定", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) return;
                    QuestionStorage.renameBank(this, info.id, name);
                    loadBanks();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showDeleteDialog(QuestionStorage.BankInfo info) {
        new AlertDialog.Builder(this)
                .setTitle("删除题库")
                .setMessage("确定删除题库「" + info.name + "」吗？\n其中的 " + info.count + " 道题和关联图片将一并删除，无法恢复！")
                .setPositiveButton("删除", (d, w) -> {
                    QuestionStorage.deleteBank(this, info.id);
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                    loadBanks();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String formatTime(long t) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        return sdf.format(new Date(t));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;

        if (requestCode == REQ_IMPORT) {
            importBankAsync(data.getData());
        } else if (requestCode == REQ_EXPORT) {
            if (pendingExportBankId == null) return;
            exportBankAsync(pendingExportBankId, data.getData());
        }
    }

    // ==================== 导入 / 导出（后台线程，避免卡死主线程） ====================

    private void exportBankAsync(String bankId, Uri uri) {
        android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setTitle("正在导出题库…");
        pd.setMessage("正在准备…");
        pd.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        pd.setCancelable(false);
        pd.show();
        new Thread(() -> {
            try {
                QuestionStorage.exportBankToUri(this, bankId, uri, (done, total) -> {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        if (total > 0) {
                            pd.setMax(total);
                            pd.setProgress(done);
                            pd.setMessage("正在写入图片 " + done + " / " + total);
                        } else {
                            pd.setMessage("正在写入图片…");
                        }
                    });
                });
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    pd.dismiss();
                    Toast.makeText(this, "导出成功", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    pd.dismiss();
                    Toast.makeText(this, "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void importBankAsync(Uri uri) {
        android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setTitle("正在导入题库…");
        pd.setMessage("正在解压文件，请稍候…");
        pd.setCancelable(false);
        pd.show();
        new Thread(() -> {
            try {
                QuestionBank bank = QuestionStorage.importBank(this, uri);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    pd.dismiss();
                    loadBanks();
                    new AlertDialog.Builder(this)
                            .setTitle("导入成功")
                            .setMessage("题库「" + bank.name + "」已导入（" + bank.questions.size() + " 题），是否现在切换？")
                            .setPositiveButton("切换", (d, w) -> {
                                QuestionStorage.switchBank(this, bank.id);
                                Toast.makeText(this, "已切换到：" + bank.name, Toast.LENGTH_SHORT).show();
                                finish();
                            })
                            .setNegativeButton("暂不", null)
                            .show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    pd.dismiss();
                    Toast.makeText(this, "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    // ==================== Adapter ====================

    private class BankAdapter extends RecyclerView.Adapter<BankAdapter.VH> {
        private List<QuestionStorage.BankInfo> items = new ArrayList<>();

        public void updateList(List<QuestionStorage.BankInfo> newList) {
            items = newList;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_bank, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            QuestionStorage.BankInfo info = items.get(position);
            holder.tvName.setText(info.name + (info.current ? "（当前）" : ""));
            holder.tvName.setTextColor(info.current ? 0xFF1f6aa5 : 0xFF222222);
            holder.tvSub.setText(info.count + " 题 · " + formatTime(info.updatedAt));
            holder.itemView.setOnClickListener(v -> {
                QuestionStorage.switchBank(SaveManageActivity.this, info.id);
                Toast.makeText(SaveManageActivity.this, "已切换到：" + info.name, Toast.LENGTH_SHORT).show();
                finish();
            });
            holder.itemView.setOnLongClickListener(v -> {
                showBankActions(info);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvSub;

            VH(View v) {
                super(v);
                tvName = v.findViewById(R.id.tv_bank_name);
                tvSub = v.findViewById(R.id.tv_bank_sub);
            }
        }
    }
}
