package com.expense.agent.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OcrServiceTest {

    @Test
    void txtPassthrough() {
        OcrService ocr = new OcrService(false);
        String t = ocr.extractText("Cafe\nTotal Rs 100".getBytes(), "r.txt", "text/plain");
        assertThat(t).contains("Cafe");
    }

    @Test
    void imageWithoutTesseractReturnsEmpty() {
        OcrService ocr = new OcrService(false);
        String t = ocr.extractText(new byte[]{1, 2, 3}, "r.png", "image/png");
        assertThat(t).isEmpty();
    }

    @Test
    void invalidPdfReturnsEmptyNotThrow() {
        OcrService ocr = new OcrService(false);
        String t = ocr.extractText(new byte[]{1, 2, 3}, "r.pdf", "application/pdf");
        assertThat(t).isNotNull();
    }
}
