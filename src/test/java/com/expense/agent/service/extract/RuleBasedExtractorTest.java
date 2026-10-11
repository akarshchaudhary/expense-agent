package com.expense.agent.service.extract;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Gap-filler engine: deterministic parsing behind the vision routes. */
class RuleBasedExtractorTest {

    RuleBasedExtractor ex = new RuleBasedExtractor();

    @Test
    void extractsCafeReceipt() {
        String ocr = "Cafe Filter Coffee\nDate: 2026-09-28\nTotal: Rs 1,240\nGSTIN 29ABCDE1234F1Z5";
        ExtractedReceipt r = ex.extract(ocr, "cafe.jpg", null, null);
        assertThat(r.vendor()).containsIgnoringCase("Cafe");
        assertThat(r.amount()).isEqualByComparingTo("1240");
        assertThat(r.expenseDate().toString()).isEqualTo("2026-09-28");
        assertThat(r.category()).isEqualTo("Food");
        assertThat(r.needsReview()).isFalse();
    }

    @Test
    void extractsUberReceipt() {
        String ocr = "Uber Trip\nDate: 2026-10-02\nAmount Payable: INR 860\nPickup HSR to Airport";
        ExtractedReceipt r = ex.extract(ocr, "uber.pdf", null, null);
        assertThat(r.vendor()).containsIgnoringCase("Uber");
        assertThat(r.amount()).isEqualByComparingTo("860");
        assertThat(r.category()).isEqualTo("Travel");
    }

    @Test
    void missingFieldsNeedReview() {
        ExtractedReceipt r = ex.extract("hello world", "mystery.jpg", null, null);
        assertThat(r.needsReview()).isTrue();
        assertThat(r.confidence()).isLessThan(0.6);
    }

    @Test
    void lastTotalWinsOverNoise() {
        String ocr = "Hotel Taj\nGST 180\nTotal: Rs 5,400\nDate 2026-09-01";
        ExtractedReceipt r = ex.extract(ocr, "hotel.pdf", null, null);
        assertThat(r.amount()).isEqualByComparingTo("5400");
    }

    @Test
    void dmyDateSupported() {
        ExtractedReceipt r = ex.extract("Swiggy\n28/09/2026\nTotal Rs 499", "s.txt", null, null);
        assertThat(r.expenseDate().toString()).isEqualTo("2026-09-28");
    }

    @Test
    void shortYearDateSupported() {
        ExtractedReceipt r = ex.extract("SRI AYODHYA CAFE\nDate : 16/08/25\nTotal: 1165", "bill.jpg", null, null);
        assertThat(r.expenseDate().toString()).isEqualTo("2025-08-16");
        assertThat(r.amount()).isEqualByComparingTo("1165");
    }

    @Test
    void usdDetected() {
        ExtractedReceipt r = ex.extract("Starbucks\n2026-09-10\nTotal $12.50", "s.txt", null, null);
        assertThat(r.currency()).isEqualTo("USD");
    }

    @Test
    void amountWithoutCurrencyDoesNotCrash() {
        ExtractedReceipt r = ex.extract("Cafe\n2026-09-28\nTotal 1240", "bill.jpg", null, null);
        assertThat(r.amount()).isEqualByComparingTo("1240");
    }

    @Test
    void droppedDecimalRestoredViaSubtotalPlusTax() {
        String ocr = "Classic Fast Food\nDate 09/06/2023\nSub Total : 1830.00\nCGST 2.5% : 45.75\nSGST 2.5% : 45.75\nFood Total : 192150";
        ExtractedReceipt r = ex.extract(ocr, "bill.jpg", null, null);
        assertThat(r.amount()).isEqualByComparingTo("1921.50");
    }

    @Test
    void noRestoreWhenArithmeticDisagrees() {
        String ocr = "Shop\n2026-09-28\nSub Total : 1830.00\nCGST : 45.75\nSGST : 45.75\nTotal 51450";
        ExtractedReceipt r = ex.extract(ocr, "bill.jpg", null, null);
        assertThat(r.amount()).isEqualByComparingTo("51450");
        assertThat(r.needsReview()).isTrue();
    }

    @Test
    void truncatedEchoTailDoesNotWin() {
        String ocr = "SRI AYODHYA CAFE\nDate: 16/08/25\nSub Total: 1111.00\n"
                + "CGST #2.5 On 1071: 26.78\nSGST #2.5 On 1071: 26.78\n6/17 Total: 1165\n"
                + "GST:29HQIP50969H121\n```json\nSRI AYODHYA CAFE\nSub Total: 1";
        ExtractedReceipt r = ex.extract(ocr, "bill.jpg", null, null);
        assertThat(r.amount()).isEqualByComparingTo("1165");
        assertThat(r.expenseDate().toString()).isEqualTo("2025-08-16");
    }

    @Test
    void reconcileFixesSubtotalGrab() {
        // Model returned Sub Total instead of Food Total: arithmetic overrules.
        String ocr = "Classic Fast Food\nDate 09/06/2023\nSub Total : 1830.00\n"
                + "CGST 2.5% : 45.75\nSGST 2.5% : 45.75\nFood Total : 1921.50";
        assertThat(RuleBasedExtractor.reconcile(ocr, new java.math.BigDecimal("1830")))
                .isEqualByComparingTo("1921.50");
    }

    @Test
    void reconcileKeepsCorrectTotal() {
        String ocr = "Cafe\nDate: 2026-09-28\nSub Total : 1830.00\nCGST : 45.75\nSGST : 45.75\nTotal: 1921.50";
        assertThat(RuleBasedExtractor.reconcile(ocr, new java.math.BigDecimal("1921.50")))
                .isEqualByComparingTo("1921.50");
        // No subtotal line: untouched, even if large
        assertThat(RuleBasedExtractor.reconcile("Total: 50000", new java.math.BigDecimal("50000")))
                .isEqualByComparingTo("50000");
        assertThat(RuleBasedExtractor.reconcile("anything", null)).isNull();
    }

    @Test
    void corroborationRejectsFabricatedTotals() {
        String text = "Food Total : 1921.50";
        assertThat(RuleBasedExtractor.corroborated(text, new java.math.BigDecimal("1921.50"))).isTrue();
        assertThat(RuleBasedExtractor.corroborated(text, new java.math.BigDecimal("734013"))).isFalse();
        assertThat(RuleBasedExtractor.corroborated("Total: 1,921.50", new java.math.BigDecimal("1921.50"))).isTrue();
        assertThat(RuleBasedExtractor.corroborated("", new java.math.BigDecimal("100"))).isFalse();
        assertThat(RuleBasedExtractor.corroborated(text, null)).isFalse();
    }
}
