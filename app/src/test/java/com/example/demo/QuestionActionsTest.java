package com.example.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class QuestionActionsTest {

    private Question normal() {
        Question q = new Question("题干", "答案");
        q.excluded = false;
        return q;
    }

    private Question excluded() {
        Question q = new Question("题干", "答案");
        q.excluded = true;
        return q;
    }

    @Test
    public void normalQuestionMenu() {
        assertArrayEquals(new String[]{"修改", "重学", "斩题", "删除"}, QuestionActions.menuLabels(normal()));
    }

    @Test
    public void excludedQuestionMenuHidesRestudy() {
        assertArrayEquals(new String[]{"修改", "恢复", "删除"}, QuestionActions.menuLabels(excluded()));
    }

    @Test
    public void deleteIsAlwaysDanger() {
        assertEquals(3, QuestionActions.dangerIndexOf(normal()));
        assertEquals(2, QuestionActions.dangerIndexOf(excluded()));
    }

    @Test
    public void kindMappingNormal() {
        Question q = normal();
        assertEquals(QuestionActions.EDIT, QuestionActions.kindAt(q, 0));
        assertEquals(QuestionActions.RESTUDY, QuestionActions.kindAt(q, 1));
        assertEquals(QuestionActions.EXCLUDE, QuestionActions.kindAt(q, 2));
        assertEquals(QuestionActions.DELETE, QuestionActions.kindAt(q, 3));
    }

    @Test
    public void kindMappingExcluded() {
        Question q = excluded();
        assertEquals(QuestionActions.EDIT, QuestionActions.kindAt(q, 0));
        assertEquals(QuestionActions.RESTORE, QuestionActions.kindAt(q, 1));
        assertEquals(QuestionActions.DELETE, QuestionActions.kindAt(q, 2));
    }

    @Test
    public void nullQuestionFallsBackToNormal() {
        assertArrayEquals(new String[]{"修改", "重学", "斩题", "删除"}, QuestionActions.menuLabels(null));
        assertEquals(QuestionActions.RESTUDY, QuestionActions.kindAt(null, 1));
    }
}
