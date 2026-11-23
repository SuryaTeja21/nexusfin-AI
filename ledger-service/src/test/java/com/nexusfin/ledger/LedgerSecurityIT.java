package com.nexusfin.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusfin.ledger.domain.model.AccountType;
import com.nexusfin.ledger.dto.CreateAccountRequest;
import com.nexusfin.ledger.dto.TransferFundsRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class LedgerSecurityIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Phase 2.3 & 2.4: Reject unauthenticated request with 401 Unauthorized")
    void shouldRejectUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Phase 2.3 & 2.4: Reject request with invalid token with 401 Unauthorized")
    void shouldRejectInvalidToken() throws Exception {
        mockMvc.perform(get("/api/v1/accounts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Phase 2.3 & 2.4: Reject request with expired token with 401 Unauthorized")
    void shouldRejectExpiredToken() throws Exception {
        mockMvc.perform(get("/api/v1/accounts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer expired-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Phase 2.3 & 2.4: CUSTOMER role attempting to create an account is rejected with 403 Forbidden")
    void customerCannotCreateAccount() throws Exception {
        CreateAccountRequest request = CreateAccountRequest.builder()
                .accountNumber("ACC-NEW-" + UUID.randomUUID())
                .accountName("Unauthorized Customer Created Account")
                .accountType(AccountType.ASSET)
                .currency("USD")
                .initialBalance(new BigDecimal("100.0000"))
                .build();

        mockMvc.perform(post("/api/v1/accounts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer customer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Phase 2.3 & 2.4: ADMIN role successfully creates account (201 Created)")
    void adminCanCreateAccount() throws Exception {
        String accNum = "ACC-ADMIN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        CreateAccountRequest request = CreateAccountRequest.builder()
                .accountNumber(accNum)
                .accountName("Admin Provisioned Treasury Account")
                .accountType(AccountType.ASSET)
                .currency("USD")
                .initialBalance(new BigDecimal("50000.0000"))
                .build();

        mockMvc.perform(post("/api/v1/accounts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountNumber").value(accNum));
    }

    @Test
    @DisplayName("Phase 2.3 & 2.4: CUSTOMER can read their own account (200 OK)")
    void customerCanReadOwnAccount() throws Exception {
        // Alice account from V4 migration: id c0000000-0000-0000-0000-000000000101, owner c0000000-0000-0000-0000-000000000001
        mockMvc.perform(get("/api/v1/accounts/c0000000-0000-0000-0000-000000000101")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer customer1-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumber").value("ACC-CUSTOMER-ALICE-USD"))
                .andExpect(jsonPath("$.ownerId").value("c0000000-0000-0000-0000-000000000001"));
    }

    @Test
    @DisplayName("Phase 2.3 & 2.4: CUSTOMER reading another customer's account is rejected with 403 Forbidden")
    void customerCannotReadOtherCustomerAccount() throws Exception {
        // Bob account from V4 migration: id c0000000-0000-0000-0000-000000000103, owner c0000000-0000-0000-0000-000000000002
        // customer1 attempts to read Bob's account
        mockMvc.perform(get("/api/v1/accounts/c0000000-0000-0000-0000-000000000103")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer customer1-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Phase 2.3 & 2.4: ADMIN can read any customer's account (200 OK)")
    void adminCanReadAnyCustomerAccount() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/c0000000-0000-0000-0000-000000000103")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumber").value("ACC-CUSTOMER-BOB-USD"));
    }

    @Test
    @DisplayName("Phase 2.3 & 2.4: CUSTOMER attempting to debit another customer's account is rejected with 403 Forbidden")
    void customerCannotDebitOtherCustomerAccount() throws Exception {
        // customer1 attempts to move money FROM Bob's account TO Alice's account
        TransferFundsRequest unauthorizedTransfer = TransferFundsRequest.builder()
                .sourceAccountNumber("ACC-CUSTOMER-BOB-USD")
                .destinationAccountNumber("ACC-CUSTOMER-ALICE-USD")
                .amount(new BigDecimal("500.0000"))
                .currency("USD")
                .idempotencyKey("STEAL-ATTEMPT-" + UUID.randomUUID())
                .transactionReference("TX-THEFT-001")
                .build();

        mockMvc.perform(post("/api/v1/ledger/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer customer1-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(unauthorizedTransfer)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Phase 2.3 & 2.4: CUSTOMER transferring from their own account succeeds (201 Created)")
    void customerCanTransferFromOwnAccount() throws Exception {
        // customer1 transfers from Alice Checking to Alice Savings
        TransferFundsRequest validTransfer = TransferFundsRequest.builder()
                .sourceAccountNumber("ACC-CUSTOMER-ALICE-USD")
                .destinationAccountNumber("ACC-CUSTOMER-ALICE-SAVINGS-USD")
                .amount(new BigDecimal("250.0000"))
                .currency("USD")
                .idempotencyKey("KEY-ALICE-" + UUID.randomUUID())
                .transactionReference("TX-ALICE-" + UUID.randomUUID().toString().substring(0, 8))
                .description("Internal wallet rebalance")
                .build();

        mockMvc.perform(post("/api/v1/ledger/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer customer1-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validTransfer)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("POSTED"));
    }
}
