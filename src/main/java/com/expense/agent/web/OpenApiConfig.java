package com.expense.agent.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI expenseApi() {
        return new OpenAPI().info(new Info()
                .title("Expense & Reimbursement Agent API")
                .version("1.0.0")
                .description("AI Innovation Lab Season 2 — receipts to submission-ready claims. "
                        + "Employee flow: create claim -> upload receipts -> answer questions -> submit. "
                        + "Finance flow: list SUBMITTED -> review (approve/reject). "
                        + "Try it: POST /api/claims, then /{id}/receipts (a .txt receipt works without OCR)."));
    }
}
