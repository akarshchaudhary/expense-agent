package com.expense.agent.service;

import com.expense.agent.domain.ExpenseItem;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class DuplicateDetectorTest {

    DuplicateDetector d = new DuplicateDetector();

    private ExpenseItem item(String vendor, String date, String amount) {
        ExpenseItem e = new ExpenseItem(UUID.randomUUID());
        e.setVendor(vendor);
        if (date != null) e.setExpenseDate(LocalDate.parse(date));
        if (amount != null) e.setAmount(new BigDecimal(amount));
        return e;
    }

    @Test
    void exactDuplicateDetected() {
        ExpenseItem a = item("Cafe Coffee", "2026-09-28", "1240");
        ExpenseItem b = item("Cafe Coffee", "2026-09-28", "1240");
        assertThat(d.isDuplicate(a, b)).isTrue();
    }

    @Test
    void differentDateNotDuplicate() {
        ExpenseItem a = item("Uber", "2026-09-28", "860");
        ExpenseItem b = item("Uber", "2026-10-02", "860");
        assertThat(d.isDuplicate(a, b)).isFalse();
    }

    @Test
    void typoVendorStillDuplicate() {
        ExpenseItem a = item("Swiggy", "2026-09-28", "499");
        ExpenseItem b = item("Swigy", "2026-09-28", "499");
        assertThat(d.isDuplicate(a, b)).isTrue();
    }

    @Test
    void amountTolerance() {
        ExpenseItem a = item("Hotel Taj", "2026-09-01", "5400");
        ExpenseItem b = item("Hotel Taj", "2026-09-01", "5405"); // within 1% / 1-unit floor
        assertThat(d.isDuplicate(a, b)).isTrue();
    }

    @Test
    void largeDifferenceNotDuplicate() {
        ExpenseItem a = item("Hotel Taj", "2026-09-01", "5400");
        ExpenseItem b = item("Hotel Taj", "2026-09-01", "6000");
        assertThat(d.isDuplicate(a, b)).isFalse();
    }

    @Test
    void findDuplicatesMapsSecondToFirst() {
        ExpenseItem a = item("Cafe", "2026-09-28", "100");
        ExpenseItem b = item("Uber", "2026-09-28", "200");
        ExpenseItem c = item("Cafe", "2026-09-28", "100");
        Map<UUID, UUID> m = d.findDuplicates(List.of(a, b, c));
        assertThat(m).containsEntry(c.getId(), a.getId());
        assertThat(m).doesNotContainKey(a.getId());
    }

    @Test
    void missingAmountNeverDuplicate() {
        ExpenseItem a = item("Cafe", "2026-09-28", null);
        ExpenseItem b = item("Cafe", "2026-09-28", null);
        assertThat(d.isDuplicate(a, b)).isFalse();
    }
}
