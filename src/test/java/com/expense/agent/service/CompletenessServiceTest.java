package com.expense.agent.service;

import com.expense.agent.domain.ExpenseItem;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class CompletenessServiceTest {

    CompletenessService s = new CompletenessService();

    @Test
    void completeItemHasNoQuestions() {
        ExpenseItem e = new ExpenseItem(UUID.randomUUID());
        e.setVendor("Uber"); e.setExpenseDate(LocalDate.now());
        e.setAmount(new BigDecimal("100")); e.setCategory("Travel");
        e.setConfidence(0.9);
        assertThat(s.questionsFor(List.of(e))).isEmpty();
    }

    @Test
    void missingFieldsEachProduceOneQuestion() {
        ExpenseItem e = new ExpenseItem(UUID.randomUUID());
        e.setSourceFile("mystery.jpg");
        List<CompletenessService.Question> qs = s.questionsFor(List.of(e));
        assertThat(qs.stream().map(CompletenessService.Question::field))
                .containsExactlyInAnyOrder("vendor", "expenseDate", "amount", "category");
    }

    @Test
    void partialMissingOnlyAsksNecessary() {
        ExpenseItem e = new ExpenseItem(UUID.randomUUID());
        e.setVendor("Cafe"); e.setAmount(new BigDecimal("50"));
        e.setExpenseDate(LocalDate.now()); e.setCategory(null);
        e.setConfidence(0.9);
        List<CompletenessService.Question> qs = s.questionsFor(List.of(e));
        assertThat(qs).hasSize(1);
        assertThat(qs.get(0).field()).isEqualTo("category");
    }

    @Test
    void lowConfidenceAmountAsksConfirm() {
        ExpenseItem e = new ExpenseItem(UUID.randomUUID());
        e.setVendor("Cafe"); e.setAmount(new BigDecimal("5000"));
        e.setExpenseDate(LocalDate.now()); e.setCategory("Food");
        e.setConfidence(0.5);
        List<CompletenessService.Question> qs = s.questionsFor(List.of(e));
        assertThat(qs.stream().map(CompletenessService.Question::field)).contains("amount");
    }
}
