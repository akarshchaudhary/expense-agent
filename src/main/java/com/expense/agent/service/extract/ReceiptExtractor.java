package com.expense.agent.service.extract;

public interface ReceiptExtractor {
    /** Provider name: gemini | glmocr */
    String name();

    /** Full extraction; bytes may be null (unit tests / text-only callers). */
    ExtractedReceipt extract(String ocrText, String fileName, byte[] rawBytes, String contentType);

    /** Convenience for text-only callers (rule fallback, tests). */
    default ExtractedReceipt extract(String ocrText, String fileName) {
        return extract(ocrText, fileName, null, null);
    }
}
