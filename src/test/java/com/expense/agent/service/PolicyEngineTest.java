package com.expense.agent.service;

import com.expense.agent.domain.ExpenseItem;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class PolicyEngineTest {

    PolicyEngine engine = new PolicyEngine(new BigDecimal("100000"), new BigDecimal("500"), 90);

    private ExpenseItem valid() {
        ExpenseItem e = new ExpenseItem(UUID.randomUUID());
        e.setVendor("Cafe");
        e.setExpenseDate(LocalDate.now().minusDays(5));
        e.setAmount(new BigDecimal("1200"));
        e.setSourceFile("r.pdf");
        e.setCategory("Food");
        return e;
    }

    @Test
    void validItemHasNoBlockingViolation() {
        List<PolicyEngine.Violation> vs = engine.checkItem(valid());
        assertThat(vs.stream().filter(PolicyEngine.Violation::blocking)).isEmpty();
    }

    @Test
    void missingAmountIsBlocking() {
        ExpenseItem e = valid();
        e.setAmount(null);
        assertThat(engine.checkItem(e)).anyMatch(v -> v.code().equals("AMOUNT_MISSING") && v.blocking());
    }

    @Test
    void futureDateBlocked() {
        ExpenseItem e = valid();
        e.setExpenseDate(LocalDate.now().plusDays(1));
        assertThat(engine.checkItem(e)).anyMatch(v -> v.code().equals("FUTURE_DATE"));
    }

    @Test
    void staleReceiptIsWarningNotBlocking() {
        ExpenseItem e = valid();
        e.setExpenseDate(LocalDate.now().minusDays(200));
        List<PolicyEngine.Violation> vs = engine.checkItem(e);
        assertThat(vs).anyMatch(v -> v.code().equals("STALE_RECEIPT") && !v.blocking());
    }

    @Test
    void entertainmentIsWarningOnly() {
        ExpenseItem e = valid();
        e.setCategory("Entertainment");
        List<PolicyEngine.Violation> vs = engine.checkItem(e);
        assertThat(vs).anyMatch(v -> v.code().equals("ENTERTAINMENT_REVIEW") && !v.blocking());
    }

    @Test
    void totalLimitBlocksClaim() {
        ExpenseItem a = valid(); a.setAmount(new BigDecimal("90000"));
        ExpenseItem b = valid(); b.setAmount(new BigDecimal("20000"));
        assertThat(engine.checkClaim(List.of(a, b))).anyMatch(v -> v.code().equals("TOTAL_LIMIT"));
    }

    @Test
    void duplicateFlaggedAsWarning() {
        ExpenseItem e = valid();
        e.setDuplicateOf(UUID.randomUUID());
        assertThat(engine.checkItem(e)).anyMatch(v -> v.code().equals("POSSIBLE_DUPLICATE") && !v.blocking());
    }
}
