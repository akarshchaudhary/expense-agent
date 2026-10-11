package com.expense.agent.service.extract;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic gap-filler behind the vision routes (never a route itself).
 * Parses vendor / date / amount / category from receipt text with regex +
 * keyword rules and document-level arithmetic validation. The vision result
 * always wins; only its null fields are filled from here.
 */
@Component("ruleExtractor")
public class RuleBasedExtractor implements ReceiptExtractor {

    private static final Pattern AMOUNT_LABELED = Pattern.compile(
            "(?i)(grand\\s*total|total|amount\\s*(due|payable)?|net\\s*payable|balance\\s*due)\\s*[:\\-]?\\s*(rs\\.?|inr|₹|\\$|usd)?\\s*([0-9]+(?:[,\\s][0-9]+)*(?:\\.[0-9]{1,2})?)");
    private static final Pattern AMOUNT_GENERIC = Pattern.compile(
            "(rs\\.?|inr|₹|\\$)\\s*([0-9]+(?:[,\\s][0-9]+)*(?:\\.[0-9]{1,2})?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_ISO = Pattern.compile("(20\\d{2})-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])");
    private static final Pattern DATE_DMY = Pattern.compile("(0[1-9]|[12][0-9]|3[01])[/\\-.](0[1-9]|1[0-2])[/\\-.](20\\d{2})");
    // Short-year Indian bills: 16/08/25 -> 2025-08-16. Pivot: 00-30 => 20xx, else 19xx.
    private static final Pattern DATE_DMY_SHORT = Pattern.compile("(0[1-9]|[12][0-9]|3[01])[/\\-.](0[1-9]|1[0-2])[/\\-.](\\d{2})(?!\\d)");
    private static final Pattern DATE_DMY2 = Pattern.compile("(0[1-9]|[12][0-9]|3[01])\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+(20\\d{2})", Pattern.CASE_INSENSITIVE);

    private static final Map<String, List<String>> CATEGORY_KEYWORDS = Map.of(
            "Food", List.of("restaurant", "cafe", "coffee", "pizza", "burger", "dhaba", "swiggy", "zomato", "meal", "lunch", "dinner", "breakfast"),
            "Travel", List.of("uber", "ola", "taxi", "cab", "flight", "indigo", "airline", "railway", "irctc", "metro", "bus", "fuel", "petrol", "diesel"),
            "Stay", List.of("hotel", "oyo", "taj", "marriott", "airbnb", "lodge", "resort", "room", "night"),
            "Office", List.of("amazon", "flipkart", "stationery", "printer", "paper", "laptop", "mouse", "keyboard", "office"),
            "Medical", List.of("pharmacy", "apollo", "hospital", "clinic", "medical", "doctor"),
            "Entertainment", List.of("movie", "pvr", "inox", "alcohol", "beer", "wine", "bar", "pub")
    );

    @Override
    public String name() { return "rule"; }

    @Override
    public ExtractedReceipt extract(String ocrText, String fileName, byte[] rawBytes, String contentType) {
        String text = ocrText == null ? "" : ocrText;
        String vendor = parseVendor(text, fileName);
        LocalDate date = parseDate(text);
        BigDecimal amount = parseAmount(text);
        String currency = parseCurrency(text);
        String category = classify(text);
        String description = buildDescription(text, vendor, category);

        double confidence = 0.4;
        boolean needsReview = false;
        if (vendor == null) { needsReview = true; } else { confidence += 0.15; }
        if (date == null) { needsReview = true; } else { confidence += 0.15; }
        if (amount == null) { needsReview = true; } else { confidence += 0.2; }
        if (category == null) { confidence -= 0.05; } else { confidence += 0.1; }
        // Photo OCR often drops decimals / joins digits (e.g. 1450 -> 51450).
        // Force human confirm for unusually large totals so demo stays honest.
        if (amount != null && amount.compareTo(new BigDecimal("20000")) > 0) {
            needsReview = true;
            confidence = Math.min(confidence, 0.55);
        }
        // Garbage vendor (no space, all-caps fragment, very short) -> confirm.
        if (vendor != null && (vendor.length() < 4 || (!vendor.contains(" ") && vendor.length() < 10))) {
            confidence = Math.min(confidence, 0.6);
            needsReview = true;
        }
        confidence = Math.max(0.1, Math.min(0.95, confidence));
        if (text.isBlank()) { confidence = 0.2; needsReview = true; }

        return new ExtractedReceipt(vendor, date, amount, currency, category, description, confidence, needsReview, "rule-based");
    }

    String parseVendor(String text, String fileName) {
        List<String> lines = Arrays.stream(text.split("\\R"))
                .map(String::trim).filter(s -> !s.isBlank())
                .filter(s -> s.length() >= 3).toList();
        // Vendor is near the top. Prefer the first multi-word alpha line (business names
        // usually have a space); single-token OCR fragments like "cLassrce" are skipped
        // when a spaced candidate follows. Digit-heavy lines (phone/GST) are skipped.
        List<String> candidates = new ArrayList<>();
        for (String line : lines) {
            String low = line.toLowerCase();
            if (low.contains("total") || low.contains("amount") || low.contains("gst")
                    || low.contains("invoice") || low.contains("date") || low.contains("tax")
                    || line.matches(".*\\d{4}-\\d{2}-\\d{2}.*")) continue;
            if (line.length() > 60) continue;
            long letters = line.chars().filter(Character::isLetter).count();
            long digits = line.chars().filter(Character::isDigit).count();
            if (letters < 3 || digits > letters) continue;
            candidates.add(line);
            if (candidates.size() >= 4) break;
        }
        for (String c : candidates) {
            if (c.contains(" ")) return cap(c.replaceAll("[^A-Za-z0-9 &.'\\-]", "").trim());
        }
        if (!candidates.isEmpty()) {
            return cap(candidates.get(0).replaceAll("[^A-Za-z0-9 &.'\\-]", "").trim());
        }
        if (!lines.isEmpty()) return cap(lines.get(0).substring(0, Math.min(48, lines.get(0).length())));
        // Fall back to filename hint: cafe-lunch-1200.txt -> cafe lunch
        if (fileName != null) {
            String base = fileName.replaceAll("\\.[a-zA-Z0-9]+$", "").replaceAll("[_\\-]+", " ").trim();
            if (!base.isBlank()) return cap(base.substring(0, Math.min(48, base.length())));
        }
        return null;
    }

    LocalDate parseDate(String text) {
        Matcher m = DATE_ISO.matcher(text);
        if (m.find()) {
            try { return LocalDate.parse(m.group(0)); } catch (DateTimeParseException ignored) {}
        }
        m = DATE_DMY.matcher(text);
        if (m.find()) {
            try {
                return LocalDate.parse(m.group(0).replace('.', '/').replace('-', '/'),
                        DateTimeFormatter.ofPattern("dd/MM/yyyy"));
            } catch (DateTimeParseException ignored) {}
        }
        m = DATE_DMY_SHORT.matcher(text);
        if (m.find()) {
            try {
                int yy = Integer.parseInt(m.group(3));
                int full = yy <= 30 ? 2000 + yy : 1900 + yy;
                return LocalDate.of(full, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
            } catch (Exception ignored) {}
        }
        m = DATE_DMY2.matcher(text);
        if (m.find()) {
            try {
                return LocalDate.parse(m.group(0).replaceAll("\\s+", " "), DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH));
            } catch (DateTimeParseException e) {
                try {
                    return LocalDate.parse(m.group(0).replaceAll("\\s+", " "), DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH));
                } catch (DateTimeParseException ignored) {}
            }
        }
        return null;
    }

    BigDecimal parseAmount(String text) {
        // Prefer the LAST labeled total (grand total sits at the bottom of bills).
        // Largest-total picking favours OCR noise like 780.00 -> 78000.
        Matcher m = AMOUNT_LABELED.matcher(text);
        List<BigDecimal> found = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        while (m.find()) {
            // group 4 = number (group 1 = label, group 2 = due/payable inner, group 3 = optional currency)
            BigDecimal v = num(m.group(4));
            if (v == null) continue;
            if (v.compareTo(new BigDecimal("999999")) > 0) continue; // phone/GSTIN noise
            found.add(v);
            ends.add(m.end(4));
        }
        // Truncated model echoes end mid-number on the final line ("Sub Total: 1").
        // Drop a trailing fragment (<3 digits, at text end) when a real total precedes it.
        if (found.size() >= 2) {
            BigDecimal last = found.get(found.size() - 1);
            int end = ends.get(ends.size() - 1);
            String tail = text.substring(Math.min(end, text.length())).trim();
            String digits = last.stripTrailingZeros().toPlainString().replaceAll("[^0-9]", "");
            if (tail.isEmpty() && digits.length() < 3) {
                found.remove(found.size() - 1);
            }
        }
        if (!found.isEmpty()) {
            BigDecimal last = found.get(found.size() - 1);
            BigDecimal restored = tryRestoreDecimal(text, last);
            if (restored != null) return restored;
            return last;
        }
        // fallback: largest money-like number with explicit Rs/INR/$ prefix
        m = AMOUNT_GENERIC.matcher(text);
        BigDecimal best = null;
        while (m.find()) {
            BigDecimal v = num(m.group(2));
            if (v == null) continue;
            if (v.compareTo(new BigDecimal("999999")) > 0) continue;
            if (best == null || v.compareTo(best) > 0) best = v;
        }
        return best;
    }

    /**
     * Provider-independent arithmetic reconciliation. Vision models often grab the
     * SUBTOTAL instead of the grand total, and OCR drops decimal dots. Given the
     * receipt text and a candidate amount:
     * - candidate ~= subtotal+tax -> keep;
     * - candidate ~= subtotal (but not total) -> return subtotal+tax (grabbed subtotal);
     * - candidate/100 ~= subtotal+tax -> dot-drop, return candidate/100;
     * - else keep the candidate.
     * Tolerance: max(1.00, 2% of expected).
     */
    public static BigDecimal reconcile(String text, BigDecimal amount) {
        if (amount == null || text == null || text.isBlank()) return amount;
        BigDecimal sub = lastDecimalOnLineMatching(text, "(?i)sub\\s*total");
        if (sub == null) return amount;
        BigDecimal tax = sumDecimalsOnLinesMatching(text, "(?i)(cgst|sgst|gst|tax)");
        BigDecimal expected = sub.add(tax).setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal tol = expected.abs().multiply(new BigDecimal("0.02")).max(new BigDecimal("1.00"));
        if (amount.subtract(expected).abs().compareTo(tol) <= 0) return amount;
        if (amount.subtract(sub).abs().compareTo(tol) <= 0) return expected;
        if (amount.scale() <= 0 && amount.compareTo(new BigDecimal("1000")) >= 0) {
            BigDecimal candidate = amount.movePointLeft(2);
            if (candidate.subtract(expected).abs().compareTo(tol) <= 0) return candidate.stripTrailingZeros();
        }
        return amount;
    }

    /**
     * Document-level validation: a TOTAL with a dropped decimal dot (514.50 -> 51450)
     * is exactly 100x the true value. Delegates to {@link #reconcile}.
     */
    BigDecimal tryRestoreDecimal(String text, BigDecimal total) {
        if (total == null || total.scale() > 0) return null; // already has decimals
        if (total.compareTo(new BigDecimal("1000")) < 0) return null;
        BigDecimal fixed = reconcile(text, total);
        return fixed != null && fixed.compareTo(total) != 0 ? fixed : null;
    }

    private static final Pattern DECIMAL_MONEY =
            // A number followed by % is a rate (CGST 2.5%), never an amount.
            Pattern.compile("([0-9]+(?:[,\\s][0-9]+)*\\.([0-9]{1,2}))(?!\\s*%)");

    static BigDecimal lastDecimalOnLineMatching(String text, String lineRegex) {
        Pattern linePat = Pattern.compile(lineRegex);
        BigDecimal last = null;
        for (String line : text.split("\\R")) {
            if (!linePat.matcher(line).find()) continue;
            Matcher m = DECIMAL_MONEY.matcher(line);
            while (m.find()) {
                try {
                    last = new BigDecimal(m.group(1).replaceAll("[,\\s]", ""));
                } catch (NumberFormatException ignored) {}
            }
        }
        return last;
    }

    static BigDecimal sumDecimalsOnLinesMatching(String text, String lineRegex) {
        Pattern linePat = Pattern.compile(lineRegex);
        BigDecimal sum = BigDecimal.ZERO;
        for (String line : text.split("\\R")) {
            if (!linePat.matcher(line).find()) continue;
            Matcher m = DECIMAL_MONEY.matcher(line);
            while (m.find()) {
                try {
                    sum = sum.add(new BigDecimal(m.group(1).replaceAll("[,\\s]", "")));
                } catch (NumberFormatException ignored) {}
            }
        }
        return sum;
    }

    String parseCurrency(String text) {
        String low = text.toLowerCase();
        if (low.contains("usd") || text.contains("$")) return "USD";
        return "INR";
    }

    /**
     * Hallucination guard: a model amount is trusted only if its digits appear in
     * the source text (exact "1921.50" or digit-run "192150" tolerating grouping
     * like "1,921.50"). A fabricated total (e.g. 734013 on a bill that never
     * contains those digits) fails and the caller falls back to parsed values.
     */
    static boolean corroborated(String text, BigDecimal amount) {
        if (text == null || text.isBlank() || amount == null) return false;
        String plain = amount.stripTrailingZeros().toPlainString();
        if (text.contains(plain)) return true;
        String digits = plain.replaceAll("[^0-9]", "");
        if (digits.length() < 2) return false;
        return text.replaceAll("[^0-9]", "").contains(digits);
    }

    String classify(String text) {
        String low = text.toLowerCase();
        String best = null;
        int bestHits = 0;
        for (var e : CATEGORY_KEYWORDS.entrySet()) {
            int hits = 0;
            for (String kw : e.getValue()) if (low.contains(kw)) hits++;
            if (hits > bestHits) { bestHits = hits; best = e.getKey(); }
        }
        return bestHits > 0 ? best : null;
    }

    private String buildDescription(String text, String vendor, String category) {
        if (category != null && vendor != null) return category + " at " + vendor;
        if (vendor != null) return "Expense at " + vendor;
        if (category != null) return category + " expense";
        return null;
    }

    private BigDecimal num(String raw) {
        if (raw == null) return null;
        try {
            String clean = raw.replaceAll("[,\\s]", "").trim();
            if (clean.isEmpty()) return null;
            return new BigDecimal(clean);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String cap(String s) {
        if (s == null || s.isBlank()) return null;
        s = s.trim().replaceAll("\\s+", " ");
        return s.length() > 48 ? s.substring(0, 48) : s;
    }
}
