package com.expense.agent.service;

import com.expense.agent.domain.ClaimStatus;
import com.expense.agent.repo.ClaimRepository;
import com.expense.agent.repo.ExpenseItemRepository;
import com.expense.agent.repo.ReceiptDocRepository;
import com.expense.agent.service.extract.ExtractedReceipt;
import com.expense.agent.service.extract.ExtractorRouter;
import com.expense.agent.service.extract.GlmOcrExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:claimtest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "app.extractor.provider=glmocr"
})
@Transactional
class ClaimServiceTest {

    @Autowired ClaimService service;
    @Autowired ClaimRepository claims;
    @Autowired ExpenseItemRepository items;
    @Autowired ReceiptDocRepository docs;
    @Autowired ExtractorRouter router;
    @MockBean GlmOcrExtractor glmocr;

    private static final ExtractedReceipt CAFE = new ExtractedReceipt(
            "Cafe Filter Coffee", LocalDate.parse("2026-09-28"), new BigDecimal("1240"),
            "INR", "Food", "Food at Cafe Filter Coffee", 0.9, false, "test");

    @BeforeEach
    void stubVision() {
        // Deterministic stand-in for the vision model: real text -> full receipt,
        // garbage -> empty (questions flow). No network in unit tests.
        when(glmocr.extract(anyString(), anyString(), any(), any())).thenAnswer(inv -> {
            String ocr = inv.getArgument(0);
            if (ocr != null && ocr.contains("Cafe")) return CAFE;
            return ExtractedReceipt.empty(0.2, "test");
        });
        when(glmocr.name()).thenReturn("glmocr");
    }

    @Test
    void uploadCreatesItemAndNeedsInfoWhenIncomplete() {
        var claim = service.create("emp-1", "trip");
        var res = service.uploadReceipts(claim.getId(), List.of(
                new ClaimService.Upload("hello world".getBytes(StandardCharsets.UTF_8), "mystery.jpg", "image/jpeg")));
        assertThat(res.created()).hasSize(1);
        var view = service.view(claim.getId());
        assertThat(view.questions()).isNotEmpty();
        assertThat(claims.findById(claim.getId()).orElseThrow().getStatus()).isEqualTo(ClaimStatus.NEEDS_INFO);
    }

    @Test
    void exactDuplicateFileSkipped() {
        var claim = service.create("emp-1", "trip");
        byte[] b = "Cafe\n2026-09-28\nTotal Rs 500".getBytes(StandardCharsets.UTF_8);
        service.uploadReceipts(claim.getId(), List.of(new ClaimService.Upload(b, "a.txt", "text/plain")));
        var res = service.uploadReceipts(claim.getId(), List.of(new ClaimService.Upload(b, "a.txt", "text/plain")));
        assertThat(res.created()).isEmpty();
        assertThat(res.skipped()).hasSize(1);
    }

    @Test
    void answerFlowReachesReadyAndSubmit() {
        var claim = service.create("emp-1", "trip");
        service.uploadReceipts(claim.getId(), List.of(
                new ClaimService.Upload("Cafe Filter Coffee\n2026-09-28\nTotal Rs 1240".getBytes(StandardCharsets.UTF_8),
                        "cafe.txt", "text/plain")));
        var view = service.view(claim.getId());
        // Rule extractor finds vendor/date/amount/category for this text -> may already be READY
        for (var q : view.questions()) {
            String v = switch (q.field()) {
                case "vendor" -> "Cafe";
                case "expenseDate" -> "2026-09-28";
                case "amount" -> "1240";
                case "category" -> "Food";
                default -> "x";
            };
            service.answer(claim.getId(), UUID.fromString(q.itemId()), q.field(), v);
        }
        var ready = service.view(claim.getId());
        assertThat(ready.questions()).isEmpty();
        var submitted = service.submit(claim.getId());
        assertThat(submitted.getStatus()).isEqualTo(ClaimStatus.SUBMITTED);
        assertThat(submitted.getMockReference()).startsWith("MOCK-");
    }

    @Test
    void submitBlockedWhenQuestionsRemain() {
        var claim = service.create("emp-1", "trip");
        service.uploadReceipts(claim.getId(), List.of(
                new ClaimService.Upload("???".getBytes(StandardCharsets.UTF_8), "x.txt", "text/plain")));
        assertThatThrownBy(() -> service.submit(claim.getId()))
                .hasMessageContaining("missing fields");
    }

    @Test
    void categoryRestrictedToFixedSet() {        var claim = service.create("emp-1", "trip");
        var res = service.uploadReceipts(claim.getId(), List.of(
                new ClaimService.Upload("Cafe\n2026-09-28\nTotal Rs 100".getBytes(StandardCharsets.UTF_8),
                        "c.txt", "text/plain")));
        var itemId = res.created().get(0).getId();
        // Unknown category rejected
        assertThatThrownBy(() -> service.answer(claim.getId(), itemId, "category", "Fun"))
                .hasMessageContaining("must be one of");
        // Known category accepted, any case
        var updated = service.answer(claim.getId(), itemId, "category", "travel");
        assertThat(updated.getCategory()).isEqualTo("Travel");
    }

    @Test
    void oversizedVisionFieldsTruncatedNot500() {        // Vision models return unbounded strings; columns are varchar(512/48).
        // Regression: long description used to 500 the upload (PG varchar(512)).
        org.mockito.Mockito.when(glmocr.extract(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ExtractedReceipt("Cafe", LocalDate.parse("2026-09-28"),
                        new BigDecimal("100"), "INR", "Food", "x".repeat(2000), 0.9, false, "test"));
        var claim = service.create("emp-1", "trip");
        var res = service.uploadReceipts(claim.getId(), List.of(
                new ClaimService.Upload("Cafe\n2026-09-28\nTotal Rs 100".getBytes(StandardCharsets.UTF_8),
                        "c.txt", "text/plain")));
        assertThat(res.created().get(0).getDescription()).hasSize(500);
        // Same guard on manual edits
        var updated = service.answer(claim.getId(), res.created().get(0).getId(), "description", "y".repeat(2000));
        assertThat(updated.getDescription()).hasSize(500);
    }

    @Test
    void visionFirstHelpers() {
        assertThat(ClaimService.isImage(new ClaimService.Upload(new byte[]{1}, "bill.webp", "image/webp"))).isTrue();
        assertThat(ClaimService.isImage(new ClaimService.Upload(new byte[]{1}, "bill.txt", "text/plain"))).isFalse();
        assertThat(ClaimService.usable(CAFE)).isTrue();
        assertThat(ClaimService.usable(ExtractedReceipt.empty(0.2, "test"))).isFalse();
        assertThat(ClaimService.usable(null)).isFalse();
    }

    @Test
    void fuzzyDuplicateFlagged() {
        var claim = service.create("emp-1", "trip");
        service.uploadReceipts(claim.getId(), List.of(
                new ClaimService.Upload("Cafe Filter Coffee\n2026-09-28\nTotal Rs 1240".getBytes(StandardCharsets.UTF_8),
                        "a.txt", "text/plain"),
                // Same vendor/date/amount but different bytes -> not exact-file dup, but fuzzy dup
                new ClaimService.Upload("Cafe Filter Coffee\nBill\n2026-09-28\nTotal Rs 1240\nThanks visit again".getBytes(StandardCharsets.UTF_8),
                        "b.txt", "text/plain")));
        var view = service.view(claim.getId());
        assertThat(view.items()).hasSize(2);
        long dups = view.items().stream().filter(i -> i.getDuplicateOf() != null).count();
        assertThat(dups).isGreaterThanOrEqualTo(1);
    }

    private ClaimService.ClaimView readyClaim() {
        var claim = service.create("emp-1", "trip");
        service.uploadReceipts(claim.getId(), List.of(
                new ClaimService.Upload("Cafe Filter Coffee\n2026-09-28\nTotal Rs 1240".getBytes(StandardCharsets.UTF_8),
                        "cafe.txt", "text/plain")));
        var view = service.view(claim.getId());
        for (var q : view.questions()) {
            String v = switch (q.field()) {
                case "vendor" -> "Cafe";
                case "expenseDate" -> "2026-09-28";
                case "amount" -> "1240";
                case "category" -> "Food";
                default -> "x";
            };
            service.answer(claim.getId(), UUID.fromString(q.itemId()), q.field(), v);
        }
        return service.view(claim.getId());
    }

    @Test
    void approveFlow() {
        var view = readyClaim();
        var submitted = service.submit(view.claim().getId());
        assertThat(submitted.getStatus()).isEqualTo(ClaimStatus.SUBMITTED);
        var approved = service.review(view.claim().getId(), true, null);
        assertThat(approved.getStatus()).isEqualTo(ClaimStatus.APPROVED);
        // Terminal: no resubmit, no re-review, no more uploads
        assertThatThrownBy(() -> service.submit(view.claim().getId()))
                .hasMessageContaining("already reviewed");
        assertThatThrownBy(() -> service.review(view.claim().getId(), true, null))
                .hasMessageContaining("only SUBMITTED");
    }

    @Test
    void rejectFlowKeepsReason() {
        var view = readyClaim();
        service.submit(view.claim().getId());
        var rejected = service.review(view.claim().getId(), false, "blurry photo");
        assertThat(rejected.getStatus()).isEqualTo(ClaimStatus.REJECTED);
        assertThat(rejected.getRejectReason()).contains("blurry");
        assertThat(service.view(view.claim().getId()).claim().getRejectReason()).contains("blurry");
    }

    @Test
    void rejectRequiresReason() {
        var view = readyClaim();
        service.submit(view.claim().getId());
        assertThatThrownBy(() -> service.review(view.claim().getId(), false, "  "))
                .hasMessageContaining("reason is required");
    }

    @Test
    void uploadedItemLinksReceiptFile() {        var claim = service.create("emp-1", "trip");
        byte[] bytes = "Cafe\n2026-09-28\nTotal Rs 100".getBytes(StandardCharsets.UTF_8);
        var res = service.uploadReceipts(claim.getId(), List.of(
                new ClaimService.Upload(bytes, "c.txt", "text/plain")));
        var item = res.created().get(0);
        assertThat(item.getReceiptId()).isNotNull();
        var view = service.view(claim.getId());
        assertThat(view.items().get(0).getReceiptId()).isEqualTo(item.getReceiptId());
        assertThat(view.receipts()).hasSize(1);
    }

    @Test
    void statusFilterListsSubmitted() {
        var view = readyClaim();
        service.submit(view.claim().getId());
        assertThat(service.listByStatus("SUBMITTED")).hasSize(1);
        assertThat(service.listByStatus("APPROVED")).isEmpty();
        assertThatThrownBy(() -> service.listByStatus("NOPE")).hasMessageContaining("unknown status");
    }
}
