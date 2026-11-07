package com.example.demo;

import java.io.Serializable;

/**
 * 一次自然日打卡记录：当天达到今日目标后的日级凭证。
 * 与单条复习会话解耦：删除复习记录不会清除打卡；题库改名/删除后 bankName 仍可显示。
 */
public class CheckinRecord implements Serializable {

    /** yyyy-MM-dd（本地时区），同一天一条 */
    public String dateKey;
    /** 当日 0 点毫秒数 */
    public long dayStart;
    /** 打卡时今日已完成题数 */
    public int done;
    /** 打卡时日预算；<=0 表示不限量 */
    public int budget;
    /** 打卡时刻 */
    public long checkedAt;
    /** 打卡时题库名快照 */
    public String bankName;

    public CheckinRecord() {
    }
}
