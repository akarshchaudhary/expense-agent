package com.expense.agent.service.extract;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Two routes: cloud vision (gemini, needs key) or offline vision (glmocr, needs Ollama). Auto picks by key. */
@Component
public class ExtractorRouter {
    private final GeminiExtractor gemini;
    private final GlmOcrExtractor glmocr;
    private final String provider;

    public ExtractorRouter(GeminiExtractor gemini, GlmOcrExtractor glmocr,
                           @Value("${app.extractor.provider:auto}") String provider) {
        this.gemini = gemini;
        this.glmocr = glmocr;
        this.provider = provider == null ? "auto" : provider.trim().toLowerCase();
    }

    public ReceiptExtractor current() {
        return switch (provider) {
            case "gemini" -> gemini;
            case "glmocr" -> glmocr;
            default -> gemini.available() ? gemini : glmocr; // auto
        };
    }

    public String currentName() { return current().name(); }

    /**
     * Vision result if usable, else an empty extraction so the questions flow
     * (never guesses) collects missing fields from the user.
     */
    public ExtractedReceipt extractWithFallback(String ocrText, String fileName, byte[] rawBytes, String contentType) {
        ReceiptExtractor primary = current();
        ExtractedReceipt r = primary.extract(ocrText, fileName, rawBytes, contentType);
        if (r == null || (r.vendor() == null && r.amount() == null && r.expenseDate() == null)) {
            return ExtractedReceipt.empty(0.2, primary.name() + "-empty");
        }
        return r;
    }
}
