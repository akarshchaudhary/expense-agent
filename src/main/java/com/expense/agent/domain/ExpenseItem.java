package com.expense.agent.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "expense_items")
public class ExpenseItem {
    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "claim_id", nullable = false)
    private UUID claimId;

    private String vendor;

    @Column(name = "expense_date")
    private LocalDate expenseDate;

    private BigDecimal amount;

    @Column(nullable = false)
    private String currency = "INR";

    private String category;

    private String description;

    @Column(name = "source_file")
    private String sourceFile;

    @Column(nullable = false)
    private double confidence = 0.5;

    @Column(name = "needs_review", nullable = false)
    private boolean needsReview = false;

    @Column(name = "duplicate_of")
    private UUID duplicateOf;

    @Column(name = "receipt_id")
    private UUID receiptId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    protected ExpenseItem() {}

    public ExpenseItem(UUID claimId) {
        this.claimId = claimId;
    }

    public UUID getId() { return id; }
    public UUID getClaimId() { return claimId; }
    public String getVendor() { return vendor; }
    public void setVendor(String vendor) { this.vendor = vendor; }
    public LocalDate getExpenseDate() { return expenseDate; }
    public void setExpenseDate(LocalDate expenseDate) { this.expenseDate = expenseDate; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { if (currency != null && !currency.isBlank()) this.currency = currency; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSourceFile() { return sourceFile; }
    public void setSourceFile(String sourceFile) { this.sourceFile = sourceFile; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    public boolean isNeedsReview() { return needsReview; }
    public void setNeedsReview(boolean needsReview) { this.needsReview = needsReview; }
    public UUID getDuplicateOf() { return duplicateOf; }
    public void setDuplicateOf(UUID duplicateOf) { this.duplicateOf = duplicateOf; }
    public UUID getReceiptId() { return receiptId; }
    public void setReceiptId(UUID receiptId) { this.receiptId = receiptId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
