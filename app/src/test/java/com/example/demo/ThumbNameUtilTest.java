package com.example.demo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * 缩略图缓存文件名规则单测：生成、反解、老 md5 名识别不出。
 */
public class ThumbNameUtilTest {

    @Test
    public void nameOf_containsStemTimeAndWidth() {
        assertEquals("209eba8c-uuid_1764000000123_w200.jpg",
                ThumbNameUtil.nameOf("209eba8c-uuid.jpg", 1764000000123L, 200));
    }

    @Test
    public void parse_roundTripsNameOf() {
        String[] p = ThumbNameUtil.parse(
                ThumbNameUtil.nameOf("3573232d-1791-473e-bbe1-7281ae62f203_ab12.jpg", 1764000000123L, 320));
        assertEquals("3573232d-1791-473e-bbe1-7281ae62f203_ab12", p[0]);
        assertEquals("1764000000123", p[1]);
        assertEquals("320", p[2]);
    }

    @Test
    public void stemOf_stripsDirectoryAndLastExtension() {
        assertEquals("a_b", ThumbNameUtil.stemOf("/data/user/0/x/app_images/a_b.jpg"));
        assertEquals("a_b", ThumbNameUtil.stemOf("a_b.png"));
        assertEquals("noext", ThumbNameUtil.stemOf("noext"));
        assertEquals("", ThumbNameUtil.stemOf(null));
    }

    @Test
    public void parse_rejectsOldMd5NamesAndMalformed() {
        assertNull(ThumbNameUtil.parse("5d41402abc4b2a76b9719d911017c592.jpg"));   // 老 md5 缓存
        assertNull(ThumbNameUtil.parse("x_123_w200.png"));                        // 后缀不对
        assertNull(ThumbNameUtil.parse("x_123_wabc.jpg"));                        // 宽度不是数字
        assertNull(ThumbNameUtil.parse("x_w200.jpg"));                            // 缺修改时间
    }
}
