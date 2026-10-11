package com.expense.agent.service;

import com.expense.agent.domain.ExpenseItem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Asks only necessary questions: one per missing/invalid core field.
 * Frontend renders these as chat prompts; answers PATCH the item.
 */
@Component
public class CompletenessService {

    public record Question(String itemId, String field, String prompt) {}

    public List<Question> questionsFor(List<ExpenseItem> items) {
        List<Question> out = new ArrayList<>();
        for (ExpenseItem it : items) {
            UUID id = it.getId();
            if (it.getVendor() == null || it.getVendor().isBlank()) {
                out.add(new Question(id.toString(), "vendor",
                        "Which vendor is '" + safe(it.getSourceFile()) + "' for?"));
            }
            if (it.getExpenseDate() == null) {
                out.add(new Question(id.toString(), "expenseDate",
                        "What date was the " + safe(it.getVendor(), "expense") + " (" + money(it) + ")? Use YYYY-MM-DD."));
            }
            if (it.getAmount() == null) {
                out.add(new Question(id.toString(), "amount",
                        "What was the total amount for " + safe(it.getVendor(), "this receipt") + "?"));
            } else if (it.getConfidence() < 0.6) {
                // OCR on phone photos often drops the decimal dot (50.00 -> 5000).
                // Force explicit confirm instead of silently submitting a wrong total.
                out.add(new Question(id.toString(), "amount",
                        "We read " + money(it) + " for " + safe(it.getVendor(), "this receipt")
                                + " (low confidence). Reply with the correct total, or repeat it to confirm."));
            }
            if (it.getCategory() == null || it.getCategory().isBlank()) {
                out.add(new Question(id.toString(), "category",
                        "Which category for " + safe(it.getVendor(), "this expense") + "? (Food/Travel/Stay/Office/Medical/Other)"));
            }
        }
        return out;
    }

    private String safe(String s) { return s == null || s.isBlank() ? "receipt" : s; }
    private String safe(String s, String fb) { return s == null || s.isBlank() ? fb : s; }
    private String money(ExpenseItem it) {
        return it.getAmount() == null ? "unknown amount" : it.getAmount() + " " + it.getCurrency();
    }
}
