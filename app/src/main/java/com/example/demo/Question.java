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
    /** 已斩：软标记，退出复习队列与长期抽查，可在列表/详情恢复；不删除题目本体 */
    public boolean excluded = false;
    /** 收藏：星标标记，仅作高亮与筛选，不影响复习队列、到期判断与打卡资格 */
    public boolean favorite = false;

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

    /** 给后台写盘用的空壳构造：绕过上面构造方法里生成 id 的格式化开销 */
    private Question() {
    }

    /** 复制一份内容一致的副本，供后台线程写盘时使用，避免和界面上的改动抢同一个对象 */
    public Question copy() {
        Question c = new Question();
        c.id = id;
        c.content = content;
        c.answer = answer;
        c.status = status;
        c.updateTime = updateTime;
        c.excluded = excluded;
        c.favorite = favorite;
        c.contentImages = contentImages == null ? null : new ArrayList<>(contentImages);
        c.answerImages = answerImages == null ? null : new ArrayList<>(answerImages);
        return c;
    }
}