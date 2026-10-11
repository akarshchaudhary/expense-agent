package com.expense.agent.web;

import com.expense.agent.service.extract.ExtractedReceipt;
import com.expense.agent.service.extract.GlmOcrExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:webtest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "app.extractor.provider=glmocr"
})
@AutoConfigureMockMvc
@Transactional
class ClaimControllerTest {

    @Autowired MockMvc mvc;
    @MockBean GlmOcrExtractor glmocr;

    @BeforeEach
    void stubVision() {
        when(glmocr.extract(anyString(), anyString(), any(), any())).thenReturn(new ExtractedReceipt(
                "Cafe Filter Coffee", LocalDate.parse("2026-09-28"), new BigDecimal("1240"),
                "INR", "Food", "Food at Cafe Filter Coffee", 0.9, false, "test"));
        when(glmocr.name()).thenReturn("glmocr");
    }

    @Test
    void fullApiFlow() throws Exception {
        String created = mvc.perform(post("/api/claims")
                        .contentType("application/json")
                        .content("{\"employeeId\":\"emp-9\",\"title\":\"demo\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        String claimId = com.jayway.jsonpath.JsonPath.read(created, "$.id");

        MockMultipartFile f = new MockMultipartFile("files", "cafe.txt", "text/plain",
                "Cafe Filter Coffee\n2026-09-28\nTotal Rs 1240".getBytes());
        mvc.perform(multipart("/api/claims/{id}/receipts", claimId).file(f))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1));

        mvc.perform(get("/api/claims/{id}", claimId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.total").value(1240));
    }

    @Test
    void healthzOk() throws Exception {
        mvc.perform(get("/healthz")).andExpect(status().isOk());
    }

    @Test
    void unknownClaim404() throws Exception {
        mvc.perform(get("/api/claims/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
    }

    @Test
    void reviewEndpointFlow() throws Exception {
        String created = mvc.perform(post("/api/claims")
                        .contentType("application/json")
                        .content("{\"employeeId\":\"emp-r\",\"title\":\"review-me\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String claimId = com.jayway.jsonpath.JsonPath.read(created, "$.id");

        MockMultipartFile f = new MockMultipartFile("files", "cafe.txt", "text/plain",
                "Cafe Filter Coffee\n2026-09-28\nTotal Rs 1240".getBytes());
        mvc.perform(multipart("/api/claims/{id}/receipts", claimId).file(f)).andExpect(status().isOk());
        mvc.perform(post("/api/claims/{id}/submit", claimId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        mvc.perform(get("/api/claims").param("status", "SUBMITTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("SUBMITTED"));

        mvc.perform(post("/api/claims/{id}/review", claimId)
                        .contentType("application/json")
                        .content("{\"approved\":false,\"reason\":\"receipt unreadable\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason").value("receipt unreadable"));

        // second review rejected: not SUBMITTED anymore
        mvc.perform(post("/api/claims/{id}/review", claimId)
                        .contentType("application/json")
                        .content("{\"approved\":true}"))
                .andExpect(status().isConflict());
    }

    @Test
    void reviewRequiresReasonAndFileRoundtrips() throws Exception {
        String created = mvc.perform(post("/api/claims")
                        .contentType("application/json")
                        .content("{\"employeeId\":\"emp-f\",\"title\":\"file-test\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String claimId = com.jayway.jsonpath.JsonPath.read(created, "$.id");

        byte[] bytes = "Cafe Filter Coffee\n2026-09-28\nTotal Rs 1240".getBytes();
        MockMultipartFile f = new MockMultipartFile("files", "cafe.txt", "text/plain", bytes);
        String uploaded = mvc.perform(multipart("/api/claims/{id}/receipts", claimId).file(f))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String receiptId = com.jayway.jsonpath.JsonPath.read(uploaded, "$.items[0].receiptId");

        // original bytes round-trip with content type
        byte[] got = mvc.perform(get("/api/claims/{id}/receipts/{receiptId}", claimId, receiptId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/plain"))
                .andReturn().getResponse().getContentAsByteArray();
        org.assertj.core.api.Assertions.assertThat(got).isEqualTo(bytes);

        // wrong claim -> 404
        mvc.perform(get("/api/claims/00000000-0000-0000-0000-000000000000/receipts/{receiptId}", receiptId))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/claims/{id}/submit", claimId)).andExpect(status().isOk());
        // reject without reason -> 400
        mvc.perform(post("/api/claims/{id}/review", claimId)
                        .contentType("application/json")
                        .content("{\"approved\":false,\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
