package com.expense.agent.service.extract;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Free-tier Gemini vision/text extractor.
 * Only used when EXTRACTOR_PROVIDER=gemini (or auto + key present).
 * Uses generateContent REST API with responseMimeType=application/json.
 * No SDK dependency so build stays offline-friendly.
 */
@Component("geminiExtractor")
public class GeminiExtractor implements ReceiptExtractor {
    private static final Logger log = LoggerFactory.getLogger(GeminiExtractor.class);
    private final RestTemplate http;
    private final String apiKey;
    private final String model;

    public GeminiExtractor(RestTemplateBuilder builder,
                           @Value("${app.gemini.api-key:}") String apiKey,
                           @Value("${app.gemini.model:gemini-2.0-flash}") String model) {
        this.http = builder.build();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
    }

    public boolean available() { return !apiKey.isBlank(); }

    @Override
    public String name() { return "gemini"; }

    @Override
    @SuppressWarnings("unchecked")
    public ExtractedReceipt extract(String ocrText, String fileName, byte[] rawBytes, String contentType) {
        if (!available()) return ExtractedReceipt.empty(0.2, "gemini key missing");
        String prompt = "You read an Indian receipt photo/scan. Extract fields as JSON with keys "
                + "vendor(string|null), expenseDate(YYYY-MM-DD|null), amount(number|null, the GRAND/TOTAL payable incl tax), "
                + "currency(INR|USD), category(Food|Travel|Stay|Office|Medical|Entertainment|null), "
                + "description(string|null), confidence(0-1). Prefer the printed GRAND TOTAL over line items. "
                + "Return ONLY JSON."
                + (ocrText != null && !ocrText.isBlank() ? "\nTesseract OCR hint (may contain errors):\n" + ocrText : "\n(No OCR hint; read the image directly.)");
        try {
            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model
                    + ":generateContent?key=" + apiKey;
            java.util.List<Map<String, Object>> parts = new java.util.ArrayList<>();
            parts.add(Map.of("text", prompt));
            // Send the actual image/PDF bytes so vision reads decimals tesseract drops (50.00 vs 5000).
            if (rawBytes != null && rawBytes.length > 0 && rawBytes.length < 12_000_000
                    && contentType != null && (contentType.startsWith("image/") || contentType.equals("application/pdf"))) {
                parts.add(Map.of("inline_data", Map.of(
                        "mime_type", contentType.startsWith("image/") ? contentType : "application/pdf",
                        "data", java.util.Base64.getEncoder().encodeToString(rawBytes))));
            }
            Map<String, Object> body = Map.of(
                    "contents", List.of(Map.of("parts", parts)),
                    "generationConfig", Map.of("responseMimeType", "application/json", "temperature", 0.1));
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<Map> res = http.exchange(url, HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
            String json = extractJsonText(res.getBody());
            return parseJson(json, ocrText);
        } catch (Exception e) {
            log.warn("gemini extract failed, falling back: {}", e.toString());
            return ExtractedReceipt.empty(0.25, "gemini error: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private String extractJsonText(Map body) {
        try {
            List<Map<String, Object>> cands = (List<Map<String, Object>>) body.get("candidates");
            Map<String, Object> content = (Map<String, Object>) cands.get(0).get("content");
            List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
            return String.valueOf(parts.get(0).get("text"));
        } catch (Exception e) {
            return "{}";
        }
    }

    private ExtractedReceipt parseJson(String json, String ocrText) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode n = om.readTree(json);
            String vendor = n.hasNonNull("vendor") ? n.get("vendor").asText(null) : null;
            LocalDate date = null;
            if (n.hasNonNull("expenseDate")) {
                try { date = LocalDate.parse(n.get("expenseDate").asText()); } catch (Exception ignored) {}
            }
            BigDecimal amount = null;
            if (n.hasNonNull("amount")) {
                try { amount = new BigDecimal(n.get("amount").asText()); } catch (Exception ignored) {}
            }
            String currency = n.hasNonNull("currency") ? n.get("currency").asText("INR") : "INR";
            String category = n.hasNonNull("category") ? n.get("category").asText(null) : null;
            String desc = n.hasNonNull("description") ? n.get("description").asText(null) : null;
            double conf = n.has("confidence") && n.get("confidence").isNumber() ? n.get("confidence").asDouble(0.7) : 0.7;
            boolean review = vendor == null || date == null || amount == null;
            // Same arithmetic guard as the offline path: subtotal-grabs and dot-drops
            // are corrected against subtotal+tax before anything is stored.
            amount = RuleBasedExtractor.reconcile(ocrText, amount);
            return new ExtractedReceipt(vendor, date, amount, currency, category, desc, conf, review, "gemini");
        } catch (Exception e) {
            return ExtractedReceipt.empty(0.25, "gemini parse error");
        }
    }
}
