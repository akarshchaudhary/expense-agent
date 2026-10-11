package com.expense.agent.service.extract;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Structured result of receipt understanding. All fields nullable except confidence. */
public record ExtractedReceipt(
        String vendor,
        LocalDate expenseDate,
        BigDecimal amount,
        String currency,
        String category,
        String description,
        double confidence,
        boolean needsReview,
        String rawHint
) {
    public static ExtractedReceipt empty(double confidence, String hint) {
        return new ExtractedReceipt(null, null, null, "INR", null, null, confidence, true, hint);
    }
}
