package com.example.demo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class Question implements Serializable {
    public String id;
    public String content;
    public List<String> contentImages;  // 存APP内部路径
    public String answer;
    public List<String> answerImages;
    public String status;
    public long updateTime;

    public Question(String content, String answer) {
        // 原来的代码
// this.id = System.currentTimeMillis() + "" + (int) (Math.random() * 9000 + 1000);

// 换成我这个 → 新题目用这个ID
        this.id = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date())
                + "_"
                + (int) (Math.random() * 900 + 100); // 3位随机数，防重复
        this.content = content;
        this.answer = answer;
        this.contentImages = new ArrayList<>();
        this.answerImages = new ArrayList<>();
        this.status = "E";
        this.updateTime = System.currentTimeMillis();
    }

    public void updateStatus(String newStatus) {
        this.status = newStatus;
        this.updateTime = System.currentTimeMillis();
    }
}