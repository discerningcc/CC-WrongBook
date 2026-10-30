package com.example.demo;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SearchView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class QuestionBankActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private SearchView searchView;
    private Adapter adapter;
    private List<Question> allQuestions;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_question_bank);

        recyclerView = findViewById(R.id.recyclerView);
        searchView = findViewById(R.id.search_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        // 只创建一次适配器（不会再重复创建）
        allQuestions = new ArrayList<>();
        adapter = new Adapter(allQuestions);
        recyclerView.setAdapter(adapter);

        // 标题栏：点击进入题库管理
        findViewById(R.id.tv_bank_title).setOnClickListener(v ->
                startActivity(new Intent(this, SaveManageActivity.class))
        );

        loadData();
        refreshTitle();

        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                filter(query);
                return false;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                filter(newText);
                return false;
            }
        });
    }

    private void refreshTitle() {
        ((TextView) findViewById(R.id.tv_bank_title))
                .setText("题库：" + QuestionStorage.getCurrentBankName(this) + " ▾");
    }

    private void loadData() {
        allQuestions.clear();
        allQuestions.addAll(QuestionStorage.loadAll(this));

        // 倒序：最新添加的在最上面
        java.util.Collections.reverse(allQuestions);

        adapter.updateList(allQuestions);
    }

    private void filter(String text) {
        List<Question> filtered = new ArrayList<>();
        for (Question q : allQuestions) {
            boolean match =
                    (q.content != null && q.content.contains(text)) ||
                            (q.answer != null && q.answer.contains(text)) ||
                            (q.id != null && q.id.contains(text));

            if (match) {
                filtered.add(q);
            }
        }
        adapter.updateList(filtered);
    }

    @Override
    protected void onResume() {
        super.onResume();
        allQuestions.clear();
        allQuestions.addAll(QuestionStorage.loadAll(this));

        // 倒序
        java.util.Collections.reverse(allQuestions);

        String keyword = searchView.getQuery().toString();
        if (!keyword.isEmpty()) {
            filter(keyword);
        } else {
            adapter.updateList(allQuestions);
        }

        // 可能刚从题库管理页切换了题库
        refreshTitle();
    }

    public static class Adapter extends RecyclerView.Adapter<Adapter.ViewHolder> {
        private List<Question> mList;

        public Adapter(List<Question> list) {
            mList = list;
        }

        public void updateList(List<Question> newList) {
            mList = newList;
            notifyDataSetChanged();
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_id, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            Question q = mList.get(position);
            holder.text.setText("题目ID：" + q.id);

            holder.itemView.setOnClickListener(v -> {
                Intent intent = new Intent(v.getContext(), QuestionDetailActivity.class);
                intent.putExtra("qid", q.id);
                v.getContext().startActivity(intent);
            });
        }

        @Override
        public int getItemCount() {
            return mList == null ? 0 : mList.size();
        }

        public static class ViewHolder extends RecyclerView.ViewHolder {
            android.widget.TextView text;

            public ViewHolder(View itemView) {
                super(itemView);
                text = itemView.findViewById(R.id.text);
            }
        }
    }
}
