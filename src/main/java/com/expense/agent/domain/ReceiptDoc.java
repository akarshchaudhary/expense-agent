package com.expense.agent.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "receipt_docs")
public class ReceiptDoc {
    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "claim_id", nullable = false)
    private UUID claimId;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "content_type", nullable = false)
    private String contentType = "application/octet-stream";

    @Column(name = "file_hash", nullable = false)
    private String fileHash;

    @Column(name = "ocr_text", nullable = false)
    private String ocrText = "";

    /** Original upload bytes for in-app preview. Demo-scale; cap 15MB at upload. */
    @Column(name = "content")
    private byte[] content;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    protected ReceiptDoc() {}

    public ReceiptDoc(UUID claimId, String fileName, String contentType, String fileHash, String ocrText) {
        this.claimId = claimId;
        this.fileName = fileName;
        this.contentType = contentType;
        this.fileHash = fileHash;
        this.ocrText = ocrText == null ? "" : ocrText;
    }

    public byte[] getContent() { return content; }
    public void setContent(byte[] content) { this.content = content; }

    public UUID getId() { return id; }
    public UUID getClaimId() { return claimId; }
    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public String getFileHash() { return fileHash; }
    public String getOcrText() { return ocrText; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
