package com.expense.agent.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "claims")
public class Claim {
    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "employee_id", nullable = false)
    private String employeeId;

    @Column(nullable = false)
    private String title = "My reimbursement";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ClaimStatus status = ClaimStatus.DRAFT;

    @Column(nullable = false)
    private String currency = "INR";

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @Column(name = "mock_reference")
    private String mockReference;

    @Column(name = "reject_reason", length = 512)
    private String rejectReason;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    protected Claim() {}

    public Claim(String employeeId, String title) {
        this.employeeId = employeeId;
        if (title != null && !title.isBlank()) this.title = title;
    }

    public UUID getId() { return id; }
    public String getEmployeeId() { return employeeId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { if (title != null && !title.isBlank()) this.title = title; }
    public ClaimStatus getStatus() { return status; }
    public void setStatus(ClaimStatus status) { this.status = status; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { if (currency != null && !currency.isBlank()) this.currency = currency; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void touch() { this.updatedAt = OffsetDateTime.now(); }
    public OffsetDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(OffsetDateTime submittedAt) { this.submittedAt = submittedAt; }
    public String getMockReference() { return mockReference; }
    public void setMockReference(String mockReference) { this.mockReference = mockReference; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }
    public OffsetDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(OffsetDateTime reviewedAt) { this.reviewedAt = reviewedAt; }
}
