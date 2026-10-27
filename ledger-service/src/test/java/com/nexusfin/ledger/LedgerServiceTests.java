package com.nexusfin.ledger;

import com.nexusfin.ledger.domain.model.AccountType;
import com.nexusfin.ledger.domain.model.EntryType;
import com.nexusfin.ledger.domain.model.TransactionType;
import com.nexusfin.ledger.dto.AccountResponse;
import com.nexusfin.ledger.dto.CreateAccountRequest;
import com.nexusfin.ledger.dto.PostTransactionRequest;
import com.nexusfin.ledger.dto.TransactionLegRequest;
import com.nexusfin.ledger.dto.TransactionResponse;
import com.nexusfin.ledger.dto.TransferFundsRequest;
import com.nexusfin.ledger.exception.InsufficientFundsException;
import com.nexusfin.ledger.exception.UnbalancedTransactionException;
import com.nexusfin.ledger.service.AccountService;
import com.nexusfin.ledger.service.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = LedgerServiceApplication.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class LedgerServiceTests {

    @Autowired
    private AccountService accountService;

    @Autowired
    private LedgerService ledgerService;

    private AccountResponse assetAccount;
    private AccountResponse liabilityAccount;

    @BeforeEach
    void setUp() {
        assetAccount = accountService.createAccount(CreateAccountRequest.builder()
                .accountNumber("ACC-ASSET-001")
                .accountName("Operating Reserve")
                .accountType(AccountType.ASSET)
                .currency("USD")
                .initialBalance(new BigDecimal("1000.0000"))
                .build());

        liabilityAccount = accountService.createAccount(CreateAccountRequest.builder()
                .accountNumber("ACC-LIAB-001")
                .accountName("Customer Wallet")
                .accountType(AccountType.LIABILITY)
                .currency("USD")
                .initialBalance(new BigDecimal("500.0000"))
                .build());
    }

    @Test
    @DisplayName("Successfully posts a balanced double-entry transaction")
    void testPostBalancedTransaction() {
        PostTransactionRequest request = PostTransactionRequest.builder()
                .transactionReference("TX-BALANCED-001")
                .idempotencyKey(UUID.randomUUID().toString())
                .transactionType(TransactionType.TRANSFER)
                .description("Balanced transfer test")
                .legs(List.of(
                        TransactionLegRequest.builder()
                                .accountNumber(assetAccount.getAccountNumber())
                                .entryType(EntryType.DEBIT)
                                .amount(new BigDecimal("100.0000"))
                                .currency("USD")
                                .build(),
                        TransactionLegRequest.builder()
                                .accountNumber(liabilityAccount.getAccountNumber())
                                .entryType(EntryType.CREDIT)
                                .amount(new BigDecimal("100.0000"))
                                .currency("USD")
                                .build()
                ))
                .build();

        TransactionResponse response = ledgerService.postTransaction(request);

        assertThat(response).isNotNull();
        assertThat(response.getTransactionReference()).isEqualTo("TX-BALANCED-001");
        assertThat(response.getEntries()).hasSize(2);

        // Asset increased by 100 -> 1100
        AccountResponse updatedAsset = accountService.getAccountByAccountNumber(assetAccount.getAccountNumber());
        assertThat(updatedAsset.getBalance()).isEqualByComparingTo("1100.0000");

        // Liability increased by 100 -> 600
        AccountResponse updatedLiability = accountService.getAccountByAccountNumber(liabilityAccount.getAccountNumber());
        assertThat(updatedLiability.getBalance()).isEqualByComparingTo("600.0000");
    }

    @Test
    @DisplayName("Rejects unbalanced transaction and throws UnbalancedTransactionException")
    void testRejectUnbalancedTransaction() {
        PostTransactionRequest request = PostTransactionRequest.builder()
                .transactionReference("TX-UNBALANCED-001")
                .idempotencyKey(UUID.randomUUID().toString())
                .transactionType(TransactionType.TRANSFER)
                .description("Unbalanced transfer")
                .legs(List.of(
                        TransactionLegRequest.builder()
                                .accountNumber(assetAccount.getAccountNumber())
                                .entryType(EntryType.DEBIT)
                                .amount(new BigDecimal("100.0000"))
                                .currency("USD")
                                .build(),
                        TransactionLegRequest.builder()
                                .accountNumber(liabilityAccount.getAccountNumber())
                                .entryType(EntryType.CREDIT)
                                .amount(new BigDecimal("50.0000"))
                                .currency("USD")
                                .build()
                ))
                .build();

        assertThatThrownBy(() -> ledgerService.postTransaction(request))
                .isInstanceOf(UnbalancedTransactionException.class);
    }

    @Test
    @DisplayName("Guarantees idempotency: repeated request returns existing transaction")
    void testIdempotencyGuarantee() {
        String key = "IDEMPOTENT-KEY-" + UUID.randomUUID();

        TransferFundsRequest transfer = TransferFundsRequest.builder()
                .sourceAccountNumber(assetAccount.getAccountNumber())
                .destinationAccountNumber(liabilityAccount.getAccountNumber())
                .amount(new BigDecimal("50.0000"))
                .currency("USD")
                .idempotencyKey(key)
                .transactionReference("TX-IDEMPOTENT-001")
                .build();

        TransactionResponse firstResponse = ledgerService.transferFunds(transfer);
        TransactionResponse secondResponse = ledgerService.transferFunds(transfer);

        assertThat(firstResponse.getId()).isEqualTo(secondResponse.getId());
        assertThat(firstResponse.getTransactionReference()).isEqualTo(secondResponse.getTransactionReference());

        // Ensure balance was deducted exactly ONCE, not twice
        AccountResponse updatedAsset = accountService.getAccountByAccountNumber(assetAccount.getAccountNumber());
        assertThat(updatedAsset.getBalance()).isEqualByComparingTo("950.0000");
    }

    @Test
    @DisplayName("Prevents overdraft on asset account and throws InsufficientFundsException")
    void testInsufficientFundsProtection() {
        TransferFundsRequest overdraftTransfer = TransferFundsRequest.builder()
                .sourceAccountNumber(assetAccount.getAccountNumber())
                .destinationAccountNumber(liabilityAccount.getAccountNumber())
                .amount(new BigDecimal("2000.0000")) // Exceeds 1000 balance
                .currency("USD")
                .idempotencyKey("KEY-OVERDRAFT")
                .transactionReference("TX-OVERDRAFT-001")
                .build();

        assertThatThrownBy(() -> ledgerService.transferFunds(overdraftTransfer))
                .isInstanceOf(InsufficientFundsException.class);
    }
}
