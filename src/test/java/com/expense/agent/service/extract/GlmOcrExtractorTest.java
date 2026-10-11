package com.expense.agent.service.extract;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * GLM-OCR path: model transcribes, rules structure. The stub stands in for
 * Ollama; assertions prove deterministic structuring of model text.
 */
class GlmOcrExtractorTest {

    private GlmOcrExtractor extractor(String baseUrl) {
        return new GlmOcrExtractor(new RestTemplateBuilder(), baseUrl, "glm-ocr", new RuleBasedExtractor());
    }

    private HttpServer stub(String transcription) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        byte[] payload = ("{\"message\":{\"content\":" + jsonQuote(transcription) + "}}")
                .getBytes(StandardCharsets.UTF_8);
        server.createContext("/api/chat", ex -> {
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, payload.length);
            ex.getResponseBody().write(payload);
            ex.close();
        });
        server.start();
        return server;
    }

    @Test
    void transcriptionStructuredByRules() throws Exception {
        String transcription = "SRI AYODHYA CAFE\nDate: 16/08/2025\nTOTAL Rs 1165";
        HttpServer server = stub(transcription);
        try {
            GlmOcrExtractor ex = extractor("http://localhost:" + server.getAddress().getPort());
            ExtractedReceipt r = ex.extract("tesseract garbage", "bill.png", new byte[]{1, 2, 3}, "image/png");
            assertThat(r.vendor()).isEqualTo("SRI AYODHYA CAFE");
            assertThat(r.expenseDate().toString()).isEqualTo("2025-08-16");
            assertThat(r.amount()).isEqualByComparingTo("1165");
            assertThat(r.category()).isEqualTo("Food");
            assertThat(r.rawHint()).isEqualTo("glmocr+rule");
            assertThat(r.needsReview()).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void classicBillYieldsGrandTotal() throws Exception {
        String transcription = "Classic Fast Food\nDate 09/06/2023\nSub Total : 1830.00\n"
                + "CGST 2.5% : 45.75\nSGST 2.5% : 45.75\nFood Total : 1921.50";
        HttpServer server = stub(transcription);
        try {
            GlmOcrExtractor ex = extractor("http://localhost:" + server.getAddress().getPort());
            ExtractedReceipt r = ex.extract("", "bill.png", new byte[]{1, 2, 3}, "image/png");
            assertThat(r.amount()).isEqualByComparingTo("1921.50");
            assertThat(r.expenseDate().toString()).isEqualTo("2023-06-09");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void serverDownFallsBackToRuleOverOcrText() {
        GlmOcrExtractor ex = extractor("http://127.0.0.1:9");
        ExtractedReceipt r = ex.extract("Cafe\n2026-09-28\nTotal Rs 1240", "cafe.jpg",
                "fake-image-bytes".getBytes(), "image/jpeg");
        assertThat(r.amount()).isEqualByComparingTo("1240");
        assertThat(r.vendor()).containsIgnoringCase("Cafe");
    }

    @Test
    void downscaleShrinksWideImages() throws Exception {
        java.util.Random rnd = new java.util.Random(42);
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                2000, 1000, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 1000; y += 10) {
            for (int x = 0; x < 2000; x += 10) {
                img.setRGB(x, y, rnd.nextInt(0xFFFFFF));
            }
        }
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", bos);
        byte[] small = GlmOcrExtractor.downscale(bos.toByteArray());
        assertThat(small).isNotNull();
        assertThat(small.length).isLessThan(bos.size());
        try (java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(small)) {
            assertThat(javax.imageio.ImageIO.read(in).getWidth()).isEqualTo(1024);
        }
    }

    @Test
    void downscaleSkipsSmallOrGarbage() {
        assertThat(GlmOcrExtractor.downscale(new byte[]{1, 2, 3})).isNull();
    }

    @Test
    void fenceEchoStrippedBeforeStructuring() {
        assertThat(GlmOcrExtractor.cleanTranscription("Total: 1165\n```json\nTotal: 1"))
                .isEqualTo("Total: 1165");
    }

    private static String jsonQuote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
