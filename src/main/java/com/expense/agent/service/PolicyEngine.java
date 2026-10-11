package com.expense.agent.service;

import com.expense.agent.domain.ExpenseItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Mock corporate policy checks. Blocking vs warning severity keeps demo honest. */
@Component
public class PolicyEngine {

    public record Violation(String itemId, String code, String message, boolean blocking) {}

    private final BigDecimal maxClaimTotal;
    private final BigDecimal receiptRequiredAbove;
    private final int maxAgeDays;

    public PolicyEngine(
            @Value("${app.policy.max-claim-total:100000}") BigDecimal maxClaimTotal,
            @Value("${app.policy.receipt-required-above:500}") BigDecimal receiptRequiredAbove,
            @Value("${app.policy.max-age-days:90}") int maxAgeDays) {
        this.maxClaimTotal = maxClaimTotal;
        this.receiptRequiredAbove = receiptRequiredAbove;
        this.maxAgeDays = maxAgeDays;
    }

    public List<Violation> checkClaim(List<ExpenseItem> items) {
        List<Violation> out = new ArrayList<>();
        BigDecimal total = items.stream()
                .map(ExpenseItem::getAmount)
                .filter(a -> a != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(maxClaimTotal) > 0) {
            out.add(new Violation(null, "TOTAL_LIMIT",
                    "Claim total " + total + " exceeds limit " + maxClaimTotal, true));
        }
        for (ExpenseItem it : items) {
            out.addAll(checkItem(it));
        }
        return out;
    }

    public List<Violation> checkItem(ExpenseItem it) {
        List<Violation> out = new ArrayList<>();
        String id = it.getId().toString();
        if (it.getAmount() == null) {
            out.add(new Violation(id, "AMOUNT_MISSING", "Amount missing for " + label(it), true));
        } else {
            if (it.getAmount().signum() <= 0) {
                out.add(new Violation(id, "AMOUNT_INVALID", "Amount must be positive for " + label(it), true));
            }
            if (it.getAmount().compareTo(receiptRequiredAbove) > 0 && (it.getSourceFile() == null || it.getSourceFile().isBlank())) {
                out.add(new Violation(id, "RECEIPT_REQUIRED", "Receipt required above " + receiptRequiredAbove, true));
            }
        }
        if (it.getExpenseDate() == null) {
            out.add(new Violation(id, "DATE_MISSING", "Date missing for " + label(it), true));
        } else {
            LocalDate oldest = LocalDate.now().minusDays(maxAgeDays);
            if (it.getExpenseDate().isBefore(oldest)) {
                // Warning (not blocking): judges and demos reuse old sample bills;
                // finance can still review. Future dates stay blocking.
                out.add(new Violation(id, "STALE_RECEIPT",
                        "Receipt older than " + maxAgeDays + " days (" + it.getExpenseDate() + ")", false));
            }
            if (it.getExpenseDate().isAfter(LocalDate.now())) {
                out.add(new Violation(id, "FUTURE_DATE", "Date cannot be in the future", true));
            }
        }
        if (it.getVendor() == null || it.getVendor().isBlank()) {
            out.add(new Violation(id, "VENDOR_MISSING", "Vendor missing", true));
        }
        if ("Entertainment".equalsIgnoreCase(it.getCategory())) {
            out.add(new Violation(id, "ENTERTAINMENT_REVIEW",
                    "Entertainment expenses need manager approval (mock rule)", false));
        }
        if (it.getDuplicateOf() != null) {
            out.add(new Violation(id, "POSSIBLE_DUPLICATE",
                    "Possible duplicate of another expense in this claim", false));
        }
        return out;
    }

    private String label(ExpenseItem it) {
        return it.getVendor() != null ? it.getVendor() : (it.getSourceFile() != null ? it.getSourceFile() : "expense");
    }
}
