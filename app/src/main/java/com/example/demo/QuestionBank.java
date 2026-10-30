package com.example.demo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 一个题库 = 一个独立命名的题目集合（含全部题目与状态）。
 * 每个题库对应 saves/ 目录下的一个 JSON 文件，文件名用 id，名称存在文件里。
 */
public class QuestionBank implements Serializable {
    public String id;            // 题库 id（同时用作文件名 save_<id>.json）
    public String name;          // 题库名称，如「数学」
    public long createdAt;       // 创建时间
    public long updatedAt;       // 最后修改时间
    public List<Question> questions = new ArrayList<>();
}
