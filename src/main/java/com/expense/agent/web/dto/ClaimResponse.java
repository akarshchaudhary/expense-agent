package com.expense.agent.web.dto;

import com.expense.agent.domain.Claim;
import com.expense.agent.domain.ExpenseItem;
import com.expense.agent.service.ClaimService;
import com.expense.agent.service.CompletenessService;
import com.expense.agent.service.PolicyEngine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ClaimResponse(
        UUID id, String employeeId, String title, String status, String currency,
        BigDecimal total, List<ExpenseItemDto> items,
        List<QuestionDto> questions, List<ViolationDto> violations,
        String mockReference, int receiptCount, String rejectReason
) {
    public static ClaimResponse from(ClaimService.ClaimView v) {
        Claim c = v.claim();
        return new ClaimResponse(
                c.getId(), c.getEmployeeId(), c.getTitle(), c.getStatus().name(), c.getCurrency(),
                v.total(),
                v.items().stream().map(ExpenseItemDto::from).toList(),
                v.questions().stream().map(QuestionDto::from).toList(),
                v.violations().stream().map(ViolationDto::from).toList(),
                c.getMockReference(), v.receipts().size(), c.getRejectReason());
    }

    public record ExpenseItemDto(UUID id, String vendor, LocalDate expenseDate, BigDecimal amount,
                                 String currency, String category, String description,
                                 String sourceFile, double confidence, boolean needsReview, UUID duplicateOf,
                                 UUID receiptId) {
        static ExpenseItemDto from(ExpenseItem e) {
            return new ExpenseItemDto(e.getId(), e.getVendor(), e.getExpenseDate(), e.getAmount(),
                    e.getCurrency(), e.getCategory(), e.getDescription(), e.getSourceFile(),
                    e.getConfidence(), e.isNeedsReview(), e.getDuplicateOf(), e.getReceiptId());
        }
    }

    public record QuestionDto(String itemId, String field, String prompt) {
        static QuestionDto from(CompletenessService.Question q) {
            return new QuestionDto(q.itemId(), q.field(), q.prompt());
        }
    }

    public record ViolationDto(String itemId, String code, String message, boolean blocking) {
        static ViolationDto from(PolicyEngine.Violation v) {
            return new ViolationDto(v.itemId(), v.code(), v.message(), v.blocking());
        }
    }
}
