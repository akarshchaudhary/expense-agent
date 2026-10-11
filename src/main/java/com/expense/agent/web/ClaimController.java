package com.expense.agent.web;

import com.expense.agent.domain.Claim;
import com.expense.agent.domain.ExpenseItem;
import com.expense.agent.domain.ReceiptDoc;
import com.expense.agent.repo.ReceiptDocRepository;
import com.expense.agent.service.ClaimService;
import com.expense.agent.service.ExpenseCategories;
import com.expense.agent.web.dto.ClaimResponse;
import jakarta.validation.constraints.NotBlank;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/claims")
@Tag(name = "claims", description = "Expense claims: create, upload receipts, review, submit, finance approve/reject")
public class ClaimController {
    private final ClaimService service;
    private final ReceiptDocRepository receiptDocs;

    public ClaimController(ClaimService service, ReceiptDocRepository receiptDocs) {
        this.service = service;
        this.receiptDocs = receiptDocs;
    }

    public record CreateClaimRequest(@NotBlank String employeeId, String title) {}
    public record AnswerRequest(String field, String value) {}
    public record UploadResponse(int created, List<ClaimResponse.ExpenseItemDto> items, List<String> skipped, String extractor) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimResponse create(@RequestBody CreateClaimRequest req) {
        Claim c = service.create(req.employeeId(), req.title());
        return ClaimResponse.from(service.view(c.getId()));
    }

    @GetMapping
    public List<ClaimResponse> list(@RequestParam(required = false) String employeeId,
                                    @RequestParam(required = false) String status) {
        if (status != null && !status.isBlank()) {
            return service.listByStatus(status).stream().map(c -> ClaimResponse.from(service.view(c.getId()))).toList();
        }
        return service.list(employeeId).stream().map(c -> ClaimResponse.from(service.view(c.getId()))).toList();
    }

    @GetMapping("/{id}")
    public ClaimResponse get(@PathVariable UUID id) {
        return ClaimResponse.from(service.view(id));
    }

    @PostMapping(value = "/{id}/receipts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadResponse upload(@PathVariable UUID id, @RequestParam("files") List<MultipartFile> files) throws Exception {
        List<ClaimService.Upload> uploads = new ArrayList<>();
        for (MultipartFile f : files) {
            uploads.add(new ClaimService.Upload(f.getBytes(),
                    f.getOriginalFilename() == null ? "receipt" : f.getOriginalFilename(),
                    f.getContentType() == null ? "application/octet-stream" : f.getContentType()));
        }
        ClaimService.UploadResult r = service.uploadReceipts(id, uploads);
        return new UploadResponse(r.created().size(),
                r.created().stream().map(e -> new ClaimResponse.ExpenseItemDto(e.getId(), e.getVendor(),
                        e.getExpenseDate(), e.getAmount(), e.getCurrency(), e.getCategory(),
                        e.getDescription(), e.getSourceFile(), e.getConfidence(), e.isNeedsReview(), e.getDuplicateOf(),
                        e.getReceiptId())).toList(),
                r.skipped(), r.extractor());
    }

    @PostMapping("/{claimId}/items/{itemId}/answers")
    public ClaimResponse.ExpenseItemDto answer(@PathVariable UUID claimId, @PathVariable UUID itemId,
                                               @RequestBody AnswerRequest req) {
        ExpenseItem e = service.answer(claimId, itemId, req.field(), req.value());
        return new ClaimResponse.ExpenseItemDto(e.getId(), e.getVendor(), e.getExpenseDate(), e.getAmount(),
                e.getCurrency(), e.getCategory(), e.getDescription(), e.getSourceFile(),
                e.getConfidence(), e.isNeedsReview(), e.getDuplicateOf(), e.getReceiptId());
    }

    @PostMapping("/{id}/submit")
    public ClaimResponse submit(@PathVariable UUID id) {
        Claim c = service.submit(id);
        return ClaimResponse.from(service.view(c.getId()));
    }

    @GetMapping("/categories")
    public List<String> categories() {
        return ExpenseCategories.ALL;
    }

    public record ReviewRequest(boolean approved, String reason) {}

    @PostMapping("/{id}/review")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Finance review: approve or reject a SUBMITTED claim (reason required to reject)")
    public ClaimResponse review(@PathVariable UUID id, @RequestBody ReviewRequest req) {
        Claim c = service.review(id, req.approved(), req.reason());
        return ClaimResponse.from(service.view(c.getId()));
    }

    @GetMapping("/{id}/receipts/{receiptId}")
    @Operation(summary = "Download the original receipt file for side-by-side verification")
    public ResponseEntity<byte[]> receiptFile(@PathVariable UUID id, @PathVariable UUID receiptId) {
        service.get(id);
        ReceiptDoc doc = receiptDocs.findById(receiptId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "receipt not found"));
        if (!doc.getClaimId().equals(id) || doc.getContent() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "receipt not found");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + doc.getFileName() + "\"")
                .contentType(MediaType.parseMediaType(doc.getContentType()))
                .body(doc.getContent());
    }
}
