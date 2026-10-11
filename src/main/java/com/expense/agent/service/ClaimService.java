package com.expense.agent.service;

import com.expense.agent.domain.*;
import com.expense.agent.repo.*;
import com.expense.agent.service.extract.ExtractedReceipt;
import com.expense.agent.service.extract.ExtractorRouter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class ClaimService {
    private final ClaimRepository claims;
    private final ExpenseItemRepository items;
    private final ReceiptDocRepository docs;
    private final OcrService ocr;
    private final ExtractorRouter router;
    private final DuplicateDetector duplicates;
    private final PolicyEngine policy;
    private final CompletenessService completeness;

    public ClaimService(ClaimRepository claims, ExpenseItemRepository items, ReceiptDocRepository docs,
                        OcrService ocr, ExtractorRouter router, DuplicateDetector duplicates,
                        PolicyEngine policy, CompletenessService completeness) {
        this.claims = claims;
        this.items = items;
        this.docs = docs;
        this.ocr = ocr;
        this.router = router;
        this.duplicates = duplicates;
        this.policy = policy;
        this.completeness = completeness;
    }

    public Claim create(String employeeId, String title) {
        Claim c = new Claim(employeeId, title);
        return claims.save(c);
    }

    public Claim get(UUID id) {
        return claims.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "claim not found"));
    }

    public List<Claim> list(String employeeId) {
        if (employeeId == null || employeeId.isBlank()) return claims.findAll();
        return claims.findByEmployeeIdOrderByCreatedAtDesc(employeeId);
    }

    public List<Claim> listByStatus(String status) {
        if (status == null || status.isBlank()) return claims.findAll();
        try {
            ClaimStatus s = ClaimStatus.valueOf(status.trim().toUpperCase());
            return claims.findAll().stream().filter(c -> c.getStatus() == s).toList();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown status: " + status);
        }
    }

    @Transactional
    public UploadResult uploadReceipts(UUID claimId, List<Upload> uploads) {
        Claim claim = get(claimId);
        if (claim.getStatus() == ClaimStatus.SUBMITTED || claim.getStatus() == ClaimStatus.APPROVED
                || claim.getStatus() == ClaimStatus.REJECTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "claim already submitted");
        }
        List<ExpenseItem> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Upload u : uploads) {
            String hash = sha256(u.bytes());
            if (docs.findByClaimIdAndFileHash(claimId, hash).isPresent()) {
                skipped.add(u.fileName() + " (exact duplicate file)");
                continue;
            }
            // Vision-first: tesseract (~5-15s/photo) runs only when vision yields
            // nothing usable. Common case skips OCR entirely; fallback keeps audit text.
            boolean visionRoute = isImage(u)
                    && (router.currentName().equals("glmocr") || router.currentName().equals("gemini"));
            String ocrText = "";
            ExtractedReceipt ex = visionRoute
                    ? router.extractWithFallback("", u.fileName(), u.bytes(), u.contentType())
                    : null;
            if (!usable(ex)) {
                ocrText = ocr.extractText(u.bytes(), u.fileName(), u.contentType());
                ex = router.extractWithFallback(ocrText, u.fileName(), u.bytes(), u.contentType());
            }
            ReceiptDoc doc = new ReceiptDoc(claimId, u.fileName(), u.contentType(), hash, ocrText);
            doc.setContent(u.bytes());
            docs.save(doc);

            ExpenseItem it = new ExpenseItem(claimId);
            it.setVendor(ex.vendor() == null ? null
                    : ex.vendor().substring(0, Math.min(48, ex.vendor().length())));
            it.setExpenseDate(ex.expenseDate());
            it.setAmount(ex.amount());
            it.setCurrency(ex.currency() == null ? claim.getCurrency() : ex.currency());
            it.setCategory(ex.category());
            // Vision descriptions are unbounded; columns are not (description 512).
            it.setDescription(ex.description() == null ? null
                    : ex.description().substring(0, Math.min(500, ex.description().length())));
            it.setSourceFile(u.fileName());
            it.setConfidence(ex.confidence());
            it.setNeedsReview(ex.needsReview());
            it.setReceiptId(doc.getId());
            created.add(items.save(it));
        }
        refreshState(claim);
        return new UploadResult(created, skipped, router.currentName());
    }

    @Transactional
    public ExpenseItem answer(UUID claimId, UUID itemId, String field, String value) {
        get(claimId);
        ExpenseItem it = items.findById(itemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "expense not found"));
        if (!it.getClaimId().equals(claimId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "item does not belong to claim");
        }
        String v = value == null ? "" : value.trim();
        switch (field.toLowerCase()) {
            case "vendor" -> it.setVendor(v.isEmpty() ? null : v.substring(0, Math.min(48, v.length())));
            case "category" -> {
                if (v.isEmpty()) { it.setCategory(null); break; }
                String norm = ExpenseCategories.normalize(v);
                if (norm == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "category must be one of: " + String.join(", ", ExpenseCategories.ALL));
                }
                it.setCategory(norm);
            }
            case "description" -> it.setDescription(v.substring(0, Math.min(500, v.length())));
            case "expensedate", "date" -> {
                try { it.setExpenseDate(LocalDate.parse(v)); }
                catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "date must be YYYY-MM-DD"); }
            }
            case "amount" -> {
                try { it.setAmount(new BigDecimal(v.replaceAll("[,\\s]", ""))); }
                catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount must be a number"); }
            }
            case "currency" -> it.setCurrency(v.toUpperCase());
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown field: " + field);
        }
        ExpenseItem saved = items.save(it);
        refreshState(claimId);
        return saved;
    }

    @Transactional
    public Claim submit(UUID claimId) {
        Claim claim = get(claimId);
        if (claim.getStatus() == ClaimStatus.SUBMITTED) return claim;
        if (claim.getStatus() == ClaimStatus.APPROVED || claim.getStatus() == ClaimStatus.REJECTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "claim already reviewed");
        }
        ClaimView view = view(claimId);
        if (!view.questions().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "resolve " + view.questions().size() + " missing fields first");
        }
        boolean blocking = view.violations().stream().anyMatch(PolicyEngine.Violation::blocking);
        if (blocking) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "resolve blocking policy violations first");
        }
        // Mock finance system: no real integration, generate deterministic reference
        claim.setStatus(ClaimStatus.SUBMITTED);
        claim.setSubmittedAt(OffsetDateTime.now());
        claim.setMockReference("MOCK-" + claim.getId().toString().substring(0, 8).toUpperCase());
        claim.touch();
        return claims.save(claim);
    }

    /** Finance review: approve or reject a SUBMITTED claim. Terminal thereafter. */
    @Transactional
    public Claim review(UUID claimId, boolean approved, String reason) {
        Claim claim = get(claimId);
        if (claim.getStatus() != ClaimStatus.SUBMITTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "only SUBMITTED claims can be reviewed");
        }
        if (approved) {
            claim.setStatus(ClaimStatus.APPROVED);
            claim.setRejectReason(null);
        } else {
            if (reason == null || reason.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rejection reason is required");
            }
            claim.setStatus(ClaimStatus.REJECTED);
            claim.setRejectReason(reason.trim());
        }
        claim.setReviewedAt(OffsetDateTime.now());
        claim.touch();
        return claims.save(claim);
    }

    @Transactional
    public void refreshState(UUID claimId) {
        refreshState(get(claimId));
    }

    void refreshState(Claim claim) {
        List<ExpenseItem> list = items.findByClaimIdOrderByCreatedAtAsc(claim.getId());
        // Recompute duplicates across whole claim
        Map<UUID, UUID> dup = duplicates.findDuplicates(list);
        for (ExpenseItem it : list) {
            UUID d = dup.get(it.getId());
            if (!Objects.equals(it.getDuplicateOf(), d)) {
                it.setDuplicateOf(d);
                items.save(it);
            }
        }
        List<CompletenessService.Question> qs = completeness.questionsFor(list);
        List<PolicyEngine.Violation> vs = policy.checkClaim(list);
        boolean blocking = vs.stream().anyMatch(PolicyEngine.Violation::blocking);
        if (claim.getStatus() != ClaimStatus.SUBMITTED
                && claim.getStatus() != ClaimStatus.APPROVED
                && claim.getStatus() != ClaimStatus.REJECTED) {
            if (list.isEmpty() || !qs.isEmpty() || blocking) {
                // Empty claim stays DRAFT; otherwise NEEDS_INFO
                claim.setStatus(list.isEmpty() ? ClaimStatus.DRAFT : ClaimStatus.NEEDS_INFO);
            } else {
                claim.setStatus(ClaimStatus.READY);
            }
            claim.touch();
            claims.save(claim);
        }
    }

    public ClaimView view(UUID claimId) {
        Claim claim = get(claimId);
        List<ExpenseItem> list = items.findByClaimIdOrderByCreatedAtAsc(claimId);
        List<ReceiptDoc> receipts = docs.findByClaimIdOrderByCreatedAtAsc(claimId);
        return new ClaimView(claim, list, receipts,
                completeness.questionsFor(list), policy.checkClaim(list));
    }

    public record Upload(byte[] bytes, String fileName, String contentType) {}
    public record UploadResult(List<ExpenseItem> created, List<String> skipped, String extractor) {}

    static boolean isImage(Upload u) {
        String n = u.fileName() == null ? "" : u.fileName().toLowerCase();
        String ct = u.contentType() == null ? "" : u.contentType().toLowerCase();
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".webp") || ct.startsWith("image/");
    }

    static boolean usable(ExtractedReceipt ex) {
        return ex != null && (ex.vendor() != null || ex.amount() != null || ex.expenseDate() != null);
    }
    public record ClaimView(Claim claim, List<ExpenseItem> items, List<ReceiptDoc> receipts,
                            List<CompletenessService.Question> questions,
                            List<PolicyEngine.Violation> violations) {
        public BigDecimal total() {
            return items.stream().map(ExpenseItem::getAmount).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(Arrays.hashCode(bytes));
        }
    }

    static String sha256(String s) {
        return sha256(s.getBytes(StandardCharsets.UTF_8));
    }
}
