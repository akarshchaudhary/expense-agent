package com.expense.agent.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * OCR facade without mandatory native deps.
 * - .txt/.csv/.json: read as text (lets demo/tests run without OCR binary)
 * - .pdf: Apache PDFBox text extraction (works for digital receipts/invoices)
 * - images (png/jpg/webp): `tesseract` CLI with receipt-tuned settings:
 *   proper file suffix (leptonica format detection), 2x upscale of small
 *   phone photos, LSTM engine (--oem 1), uniform-block mode (--psm 6).
 *   If tesseract is absent, returns "" and the extractor falls back to
 *   filename hints + user Q&A.
 */
@Component
public class OcrService {
    private static final Logger log = LoggerFactory.getLogger(OcrService.class);
    private final boolean tesseractPresent;

    public OcrService() {
        this.tesseractPresent = detectTesseract();
    }

    // Visible for tests
    OcrService(boolean tesseractPresent) {
        this.tesseractPresent = tesseractPresent;
    }

    public String extractText(byte[] bytes, String fileName, String contentType) {
        String name = fileName == null ? "" : fileName.toLowerCase();
        String ct = contentType == null ? "" : contentType.toLowerCase();
        try {
            if (name.endsWith(".txt") || name.endsWith(".csv") || name.endsWith(".json")
                    || ct.startsWith("text/") || ct.equals("application/json")) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
            if (name.endsWith(".pdf") || ct.equals("application/pdf")) {
                return extractPdf(bytes);
            }
            if (tesseractPresent && (name.endsWith(".png") || name.endsWith(".jpg")
                    || name.endsWith(".jpeg") || name.endsWith(".webp") || ct.startsWith("image/"))) {
                return runTesseract(bytes, fileName, contentType);
            }
            return "";
        } catch (Exception e) {
            log.warn("ocr failed for {}: {}", fileName, e.toString());
            return "";
        }
    }

    String extractPdf(byte[] bytes) throws Exception {
        try (InputStream in = new ByteArrayInputStream(bytes); PDDocument doc = PDDocument.load(in)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String t = stripper.getText(doc);
            return t == null ? "" : t.trim();
        }
    }

    private String runTesseract(byte[] bytes, String fileName, String contentType) {
        try {
            // Leptonica detects format from suffix: an extensionless ".img" file mis-decodes
            // some uploads, so keep a real image suffix. Prefer PNG (upscaled) when readable.
            String suffix = suffixFor(fileName, contentType);
            byte[] payload = maybeUpscale(bytes);
            if (payload != null) suffix = ".png";
            else payload = bytes;
            java.nio.file.Path img = java.nio.file.Files.createTempFile("receipt-", suffix);
            java.nio.file.Path out = java.nio.file.Files.createTempFile("receipt-", ".txt");
            try {
                java.nio.file.Files.write(img, payload);
                // Receipt-tuned: LSTM engine + uniform text block. Default PSM 3 (auto page
                // segmentation) shreds narrow bills into wrong line order and drops '.'.
                Process p = new ProcessBuilder("tesseract", img.toString(),
                        out.toString().replaceAll("\\.txt$", ""), "-l", "eng",
                        "--oem", "1", "--psm", "6")
                        .redirectErrorStream(true).start();
                boolean done = p.waitFor(45, java.util.concurrent.TimeUnit.SECONDS);
                if (!done) { p.destroyForcibly(); return ""; }
                if (p.exitValue() != 0) {
                    log.warn("tesseract exit={} for {}", p.exitValue(), fileName);
                    return "";
                }
                java.nio.file.Path txt = java.nio.file.Path.of(out.toString().replaceAll("\\.txt$", "") + ".txt");
                if (java.nio.file.Files.exists(txt)) return java.nio.file.Files.readString(txt).trim();
                return "";
            } finally {
                try { java.nio.file.Files.deleteIfExists(img); } catch (Exception ignored) {}
                try { java.nio.file.Files.deleteIfExists(out); } catch (Exception ignored) {}
                try { java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(out.toString().replaceAll("\\.txt$", "") + ".txt")); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            log.warn("tesseract run failed: {}", e.toString());
            return "";
        }
    }

    private String suffixFor(String fileName, String contentType) {
        String n = fileName == null ? "" : fileName.toLowerCase();
        String ct = contentType == null ? "" : contentType.toLowerCase();
        if (n.endsWith(".png") || ct.equals("image/png")) return ".png";
        if (n.endsWith(".webp") || ct.equals("image/webp")) return ".webp";
        if (n.endsWith(".tif") || n.endsWith(".tiff")) return ".tif";
        return ".jpg";
    }

    /**
     * Phone photos under ~1500px wide get 2x bilinear upscale to PNG.
     * Returns null when bytes aren't decodable (e.g. webp without codec) so the
     * caller falls back to raw bytes with a proper suffix.
     */
    private byte[] maybeUpscale(byte[] bytes) {
        try (java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(bytes)) {
            java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(in);
            if (src == null || src.getWidth() <= 0) return null;
            if (src.getWidth() >= 1500) return null; // already high-res; skip resample artefacts
            int w = src.getWidth() * 2, h = src.getHeight() * 2;
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
                javax.imageio.ImageIO.write(dst, "png", bos);
                return bos.toByteArray();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private boolean detectTesseract() {
        try {
            Process p = new ProcessBuilder("tesseract", "--version").redirectErrorStream(true).start();
            boolean done = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            return done && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isTesseractPresent() { return tesseractPresent; }
}
