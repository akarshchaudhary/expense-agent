package com.expense.agent.service;

import com.expense.agent.domain.ExpenseItem;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * Duplicate detection: exact file hash handled at upload; here fuzzy match on
 * normalized vendor + date + amount (1% tolerance) flags possible re-submits.
 */
@Component
public class DuplicateDetector {

    public Map<UUID, UUID> findDuplicates(List<ExpenseItem> items) {
        Map<UUID, UUID> dupOf = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            ExpenseItem a = items.get(i);
            for (int j = 0; j < i; j++) {
                ExpenseItem b = items.get(j);
                if (isDuplicate(a, b)) {
                    dupOf.put(a.getId(), b.getId());
                    break;
                }
            }
        }
        return dupOf;
    }

    public boolean isDuplicate(ExpenseItem a, ExpenseItem b) {
        if (a.getAmount() == null || b.getAmount() == null) return false;
        if (!sameVendor(a.getVendor(), b.getVendor())) return false;
        if (a.getExpenseDate() != null && b.getExpenseDate() != null
                && !a.getExpenseDate().equals(b.getExpenseDate())) return false;
        // If either date missing, require exact amount match to avoid false positives
        if (a.getExpenseDate() == null || b.getExpenseDate() == null) {
            return a.getAmount().compareTo(b.getAmount()) == 0;
        }
        return amountsClose(a.getAmount(), b.getAmount());
    }

    boolean sameVendor(String x, String y) {
        if (x == null || y == null) return false;
        String nx = normalize(x), ny = normalize(y);
        if (nx.isEmpty() || ny.isEmpty()) return false;
        if (nx.equals(ny)) return true;
        // Small typo tolerance: one contains the other, or edit distance <= 2 for short names
        if (nx.contains(ny) || ny.contains(nx)) return true;
        return levenshtein(nx, ny) <= 2;
    }

    String normalize(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    boolean amountsClose(BigDecimal a, BigDecimal b) {
        BigDecimal diff = a.subtract(b).abs();
        BigDecimal tol = b.abs().multiply(new BigDecimal("0.01")).max(new BigDecimal("1.00"));
        return diff.compareTo(tol) <= 0;
    }

    int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            prev = cur;
        }
        return prev[b.length()];
    }
}
