package com.expense.agent.service.extract;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline vision path: GLM-OCR (0.9B, MIT) reads the photo, then structures it.
 * Two model calls over Ollama /api/chat: (1) plain-text transcription (the model's
 * specialty: decimals, tables, stylised fonts), (2) that transcription rendered as
 * strict JSON. No regex anywhere: structuring is done by the model, parsing by Jackson.
 * Anything unusable degrades to an empty extraction so the questions flow (not guesses)
 * collects the missing fields from the user.
 */
@Component("glmOcrExtractor")
public class GlmOcrExtractor implements ReceiptExtractor {
    private static final Logger log = LoggerFactory.getLogger(GlmOcrExtractor.class);
    private final RestTemplate http;
    private final String baseUrl;
    private final String model;
    private final RuleBasedExtractor rule;

    public GlmOcrExtractor(RestTemplateBuilder builder,
                           @Value("${app.ollama.base-url:http://localhost:11434}") String baseUrl,
                           @Value("${app.glmocr.model:glm-ocr}") String model,
                           RuleBasedExtractor rule) {
        this.http = builder.build();
        this.baseUrl = baseUrl;
        this.model = model;
        this.rule = rule;
    }

    @Override
    public String name() { return "glmocr"; }

    @Override
    public ExtractedReceipt extract(String ocrText, String fileName, byte[] rawBytes, String contentType) {
        try {
            boolean hasImage = rawBytes != null && rawBytes.length > 0 && rawBytes.length < 12_000_000
                    && contentType != null && contentType.startsWith("image/");
            String source = hasImage ? transcribe(rawBytes) : ocrText;
            if (source == null || source.isBlank()) source = ocrText == null ? "" : ocrText;
            source = cleanTranscription(source);
            if (source.isBlank()) return ExtractedReceipt.empty(0.2, "glmocr-empty");
            if (log.isInfoEnabled() && hasImage) {
                String preview = source.length() > 1200 ? source.substring(0, 1200) + "…" : source;
                log.info("glm-ocr transcription for {} ({} chars):\n{}", fileName, source.length(), preview);
            }
            // Single model call. Structuring is deterministic: the transcription is
            // parsed by rules (last-total-wins, short dates, arithmetic restore), so a
            // structuring hallucination like 734013 is architecturally impossible.
            ExtractedReceipt r = rule.extract(source, fileName, rawBytes, contentType);
            return new ExtractedReceipt(r.vendor(), r.expenseDate(), r.amount(), r.currency(),
                    r.category(), r.description(), r.confidence(), r.needsReview(), "glmocr+rule");
        } catch (Exception e) {
            log.warn("glm-ocr unavailable ({}), rule gap-fill over OCR text", e.toString());
            return rule.extract(ocrText, fileName, rawBytes, contentType);
        }
    }

    @SuppressWarnings("unchecked")
    String transcribe(byte[] imageBytes) {
        byte[] shrunk = downscale(imageBytes);
        byte[] payload = shrunk == null ? imageBytes : shrunk;
        Map<String, Object> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", "Transcribe ALL visible text in this receipt image exactly as printed. "
                + "Preserve every digit and decimal point (e.g. 514.50, never 51450), line breaks, "
                + "and table rows. Output the transcription ONCE as plain text only: "
                + "no code fences, no repetition, no commentary, no JSON.");
        message.put("images", List.of(java.util.Base64.getEncoder().encodeToString(payload)));
        return chat(message);
    }

    @SuppressWarnings("unchecked")
    private String chat(Map<String, Object> message) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("stream", false);
        body.put("messages", List.of(message));
        // Small context: receipt prompts are short. Default 4096 wastes VRAM on KV cache.
        // Single transcription call: fits in 768 tokens.
        body.put("options", Map.of("temperature", 0.0, "num_ctx", 2048, "num_predict", 768));
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> res = http.exchange(baseUrl + "/api/chat", HttpMethod.POST,
                new HttpEntity<>(body, h), Map.class);
        Map msg = (Map) (res.getBody().get("message") != null ? res.getBody().get("message") : Map.of());
        return String.valueOf(msg.getOrDefault("content", ""));
    }

    /**
     * Models often wrap the transcription in ``` fences and echo the receipt a
     * second time. Structure only the first transcription: cut at the first fence.
     */
    static String cleanTranscription(String transcription) {
        if (transcription == null) return "";
        int fence = transcription.indexOf("```");
        String first = (fence < 0 ? transcription : transcription.substring(0, fence)).trim();
        return first.isEmpty() ? transcription.trim() : first;
    }

    /**
     * Downscales receipt photos to max 1024px wide JPEG before sending.
     * Vision tokens scale with pixels: a 4000px phone photo costs ~16x a 1024px
     * one for no accuracy gain on printed bills. Returns null when the bytes
     * are undecodable (caller then sends raw bytes).
     */
    static byte[] downscale(byte[] rawBytes) {
        try (java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(rawBytes)) {
            java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(in);
            if (src == null || src.getWidth() <= 1024) return null; // small enough or undecodable
            double ratio = 1024.0 / src.getWidth();
            int w = 1024, h = Math.max(1, (int) (src.getHeight() * ratio));
            java.awt.image.BufferedImage dst = new java.awt.image.BufferedImage(
                    w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = dst.createGraphics();
            try {
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(src, 0, 0, w, h, java.awt.Color.WHITE, null);
            } finally {
                g.dispose();
            }
            try (java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
                javax.imageio.ImageIO.write(dst, "jpg", bos);
                return bos.toByteArray();
            }
        } catch (Exception e) {
            return null;
        }
    }
}
