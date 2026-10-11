package com.expense.agent.service;

import java.util.List;

/** Fixed category set: UI renders these as a dropdown, API enforces them. */
public final class ExpenseCategories {
    public static final List<String> ALL = List.of(
            "Food", "Travel", "Stay", "Office", "Medical", "Entertainment", "Other");

    private ExpenseCategories() {}

    /** Normalizes user/LLM input ("food", "FOOD ") to canonical form, or null if unknown. */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        for (String c : ALL) {
            if (c.equalsIgnoreCase(t)) return c;
        }
        return null;
    }
}
