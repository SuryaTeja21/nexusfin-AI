package com.nexusfin.ledger.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusfin.ledger.domain.entity.Account;
import com.nexusfin.ledger.domain.entity.Transaction;
import com.nexusfin.ledger.domain.model.AccountStatus;
import com.nexusfin.ledger.domain.model.AccountType;
import com.nexusfin.ledger.domain.model.EntryType;
import com.nexusfin.ledger.domain.model.TransactionStatus;
import com.nexusfin.ledger.domain.model.TransactionType;
import com.nexusfin.ledger.dto.PostTransactionRequest;
import com.nexusfin.ledger.dto.TransactionLegRequest;
import com.nexusfin.ledger.dto.TransactionResponse;
import com.nexusfin.ledger.dto.TransferFundsRequest;
import com.nexusfin.ledger.exception.CurrencyMismatchException;
import com.nexusfin.ledger.exception.InsufficientFundsException;
import com.nexusfin.ledger.exception.InvalidAccountStateException;
import com.nexusfin.ledger.exception.LedgerException;
import com.nexusfin.ledger.exception.UnbalancedTransactionException;
import com.nexusfin.ledger.repository.AccountRepository;
import com.nexusfin.ledger.repository.LedgerEntryRepository;
import com.nexusfin.ledger.repository.OutboxEventRepository;
import com.nexusfin.ledger.repository.TransactionRepository;
import com.nexusfin.ledger.repository.projection.AccountIdProjection;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerServiceUnitTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private EntityManager entityManager;

    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private LedgerService ledgerService;

    @BeforeEach
    void setUp() {
        // By default, if transactionTemplate is called, execute the callback directly
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    private AccountIdProjection mockProjection(String accountNumber, UUID id, AccountType type) {
        AccountIdProjection proj = mock(AccountIdProjection.class);
        lenient().when(proj.getAccountNumber()).thenReturn(accountNumber);
        lenient().when(proj.getId()).thenReturn(id);
        lenient().when(proj.getAccountType()).thenReturn(type);
        return proj;
    }

    @Test
    @DisplayName("Validation: Must reject transaction with fewer than 2 legs")
    void shouldRejectTransactionWithFewerThanTwoLegs() {
        PostTransactionRequest request = PostTransactionRequest.builder()
                .transactionReference("TX-SINGLE-LEG")
                .transactionType(TransactionType.TRANSFER)
                .legs(List.of(
                        TransactionLegRequest.builder()
                                .accountNumber("ACC-001")
                                .entryType(EntryType.DEBIT)
                                .amount(new BigDecimal("100.0000"))
                                .currency("USD")
                                .build()
                ))
                .build();

        assertThatThrownBy(() -> ledgerService.postTransaction(request))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("at least 2 legs");
    }

    @Test
    @DisplayName("Validation: Must reject transaction with non-positive (zero or negative) leg amount")
    void shouldRejectNonPositiveLegAmount() {
        // Test Zero Amount
        PostTransactionRequest zeroRequest = PostTransactionRequest.builder()
                .transactionReference("TX-ZERO")
                .transactionType(TransactionType.TRANSFER)
                .legs(List.of(
                        TransactionLegRequest.builder()
                                .accountNumber("ACC-001")
                                .entryType(EntryType.DEBIT)
                                .amount(BigDecimal.ZERO)
                                .currency("USD")
                                .build(),
                        TransactionLegRequest.builder()
                                .accountNumber("ACC-002")
                                .entryType(EntryType.CREDIT)
                                .amount(BigDecimal.ZERO)
                                .currency("USD")
                                .build()
                ))
                .build();

        assertThatThrownBy(() -> ledgerService.postTransaction(zeroRequest))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("positive");

        // Test Negative Amount
        PostTransactionRequest negativeRequest = PostTransactionRequest.builder()
                .transactionReference("TX-NEGATIVE")
                .transactionType(TransactionType.TRANSFER)
                .legs(List.of(
                        TransactionLegRequest.builder()
                                .accountNumber("ACC-001")
                                .entryType(EntryType.DEBIT)
                                .amount(new BigDecimal("-50.0000"))
                                .currency("USD")
                                .build(),
                        TransactionLegRequest.builder()
                                .accountNumber("ACC-002")
                                .entryType(EntryType.CREDIT)
                                .amount(new BigDecimal("-50.0000"))
                                .currency("USD")
                                .build()
                ))
                .build();

        assertThatThrownBy(() -> ledgerService.postTransaction(negativeRequest))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("positive");
    }

    @Test
    @DisplayName("Validation: Must reject transfer with non-positive (zero or negative) amount")
    void shouldRejectNonPositiveTransferAmount() {
        TransferFundsRequest zeroTransfer = TransferFundsRequest.builder()
                .sourceAccountNumber("ACC-SRC")
                .destinationAccountNumber("ACC-DST")
                .amount(BigDecimal.ZERO)
                .currency("USD")
                .build();

        assertThatThrownBy(() -> ledgerService.transferFunds(zeroTransfer))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("positive");

        TransferFundsRequest negativeTransfer = TransferFundsRequest.builder()
                .sourceAccountNumber("ACC-SRC")
                .destinationAccountNumber("ACC-DST")
                .amount(new BigDecimal("-25.0000"))
                .currency("USD")
                .build();

        assertThatThrownBy(() -> ledgerService.transferFunds(negativeTransfer))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("positive");
    }

    @Test
    @DisplayName("Validation: Must reject amounts with more than 4 decimal places with LedgerException")
    void shouldRejectAmountsWithMoreThanFourDecimals() {
        // 1. In postTransaction
        PostTransactionRequest postRequest = PostTransactionRequest.builder()
                .transactionReference("TX-DECIMALS")
                .transactionType(TransactionType.TRANSFER)
                .legs(List.of(
                        TransactionLegRequest.builder()
                                .accountNumber("ACC-001")
                                .entryType(EntryType.DEBIT)
                                .amount(new BigDecimal("10.12345"))
                                .currency("USD")
                                .build(),
                        TransactionLegRequest.builder()
                                .accountNumber("ACC-002")
                                .entryType(EntryType.CREDIT)
                                .amount(new BigDecimal("10.12345"))
                                .currency("USD")
                                .build()
                ))
                .build();

        assertThatThrownBy(() -> ledgerService.postTransaction(postRequest))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("at most 4 decimal places");

        // 2. In transferFunds
        TransferFundsRequest transferRequest = TransferFundsRequest.builder()
                .sourceAccountNumber("ACC-001")
                .destinationAccountNumber("ACC-002")
                .amount(new BigDecimal("10.12345"))
                .currency("USD")
                .build();

        assertThatThrownBy(() -> ledgerService.transferFunds(transferRequest))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("at most 4 decimal places");
    }

    @Test
    @DisplayName("Validation: Must reject unbalanced double-entry transaction")
    void shouldRejectUnbalancedTransaction() {
        PostTransactionRequest request = PostTransactionRequest.builder()
                .transactionReference("TX-UNBALANCED")
                .transactionType(TransactionType.TRANSFER)
                .legs(List.of(
                        TransactionLegRequest.builder()
                                .accountNumber("ACC-001")
                                .entryType(EntryType.DEBIT)
                                .amount(new BigDecimal("100.0000"))
                                .currency("USD")
                                .build(),
                        TransactionLegRequest.builder()
                                .accountNumber("ACC-002")
                                .entryType(EntryType.CREDIT)
                                .amount(new BigDecimal("90.0000"))
                                .currency("USD")
                                .build()
                ))
                .build();

        assertThatThrownBy(() -> ledgerService.postTransaction(request))
                .isInstanceOf(UnbalancedTransactionException.class);
    }

    @Test
    @DisplayName("Validation: Must reject inactive account")
    void shouldRejectInactiveAccount() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        Account acc1 = Account.builder()
                .id(id1)
                .accountNumber("ACC-INACTIVE")
                .accountName("Frozen Account")
                .accountType(AccountType.ASSET)
                .currency("USD")
                .balance(new BigDecimal("1000.0000"))
                .status(AccountStatus.FROZEN)
                .build();

        Account acc2 = Account.builder()
                .id(id2)
                .accountNumber("ACC-ACTIVE")
                .accountName("Active Account")
                .accountType(AccountType.ASSET)
                .currency("USD")
                .balance(new BigDecimal("1000.0000"))
                .status(AccountStatus.ACTIVE)
                .build();

        AccountIdProjection projInact = mockProjection("ACC-INACTIVE", id1, AccountType.ASSET);
        AccountIdProjection projAct = mockProjection("ACC-ACTIVE", id2, AccountType.ASSET);
        when(accountRepository.findAccountIdsByAccountNumberIn(any())).thenReturn(List.of(projInact, projAct));
        when(accountRepository.findAllByIdsWithPessimisticLock(any())).thenReturn(List.of(acc1, acc2));

        PostTransactionRequest request = PostTransactionRequest.builder()
                .transactionReference("TX-FROZEN")
                .transactionType(TransactionType.TRANSFER)
                .legs(List.of(
                        TransactionLegRequest.builder()
                                .accountNumber("ACC-INACTIVE")
                                .entryType(EntryType.CREDIT)
                                .amount(new BigDecimal("100.0000"))
                                .currency("USD")
                                .build(),
                        TransactionLegRequest.builder()
                                .accountNumber("ACC-ACTIVE")
                                .entryType(EntryType.DEBIT)
                                .amount(new BigDecimal("100.0000"))
                                .currency("USD")
                                .build()
                ))
                .build();

        assertThatThrownBy(() -> ledgerService.postTransaction(request))
                .isInstanceOf(InvalidAccountStateException.class)
                .hasMessageContaining("ACC-INACTIVE");
    }

    @Test
    @DisplayName("Validation: Must reject currency mismatch between leg and account")
    void shouldRejectCurrencyMismatch() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        Account acc1 = Account.builder()
                .id(id1)
                .accountNumber("ACC-USD-1")
                .accountName("USD Account")
                .accountType(AccountType.ASSET)
                .currency("USD")
                .balance(new BigDecimal("1000.0000"))
                .status(AccountStatus.ACTIVE)
                .build();

        Account acc2 = Account.builder()
                .id(id2)
                .accountNumber("ACC-USD-2")
                .accountName("USD Account 2")
                .accountType(AccountType.ASSET)
                .currency("USD")
                .balance(new BigDecimal("1000.0000"))
                .status(AccountStatus.ACTIVE)
                .build();

        AccountIdProjection projUSD1 = mockProjection("ACC-USD-1", id1, AccountType.ASSET);
        AccountIdProjection projUSD2 = mockProjection("ACC-USD-2", id2, AccountType.ASSET);
        when(accountRepository.findAccountIdsByAccountNumberIn(any())).thenReturn(List.of(projUSD1, projUSD2));
        when(accountRepository.findAllByIdsWithPessimisticLock(any())).thenReturn(List.of(acc1, acc2));

        PostTransactionRequest request = PostTransactionRequest.builder()
                .transactionReference("TX-EUR-ON-USD")
                .transactionType(TransactionType.TRANSFER)
                .legs(List.of(
                        TransactionLegRequest.builder()
                                .accountNumber("ACC-USD-1")
                                .entryType(EntryType.CREDIT)
                                .amount(new BigDecimal("100.0000"))
                                .currency("EUR")
                                .build(),
                        TransactionLegRequest.builder()
                                .accountNumber("ACC-USD-2")
                                .entryType(EntryType.DEBIT)
                                .amount(new BigDecimal("100.0000"))
                                .currency("EUR")
                                .build()
                ))
                .build();

        assertThatThrownBy(() -> ledgerService.postTransaction(request))
                .isInstanceOf(CurrencyMismatchException.class)
                .hasMessageContaining("EUR");
    }

    @Test
    @DisplayName("Validation: Must reject transfer if asset account balance would become negative")
    void shouldRejectInsufficientFunds() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        Account acc1 = Account.builder()
                .id(id1)
                .accountNumber("ACC-POOR")
                .accountName("Low Balance")
                .accountType(AccountType.ASSET)
                .currency("USD")
                .balance(new BigDecimal("50.0000"))
                .status(AccountStatus.ACTIVE)
                .build();

        Account acc2 = Account.builder()
                .id(id2)
                .accountNumber("ACC-RICH")
                .accountName("Destination")
                .accountType(AccountType.ASSET)
                .currency("USD")
                .balance(new BigDecimal("1000.0000"))
                .status(AccountStatus.ACTIVE)
                .build();

        AccountIdProjection projPoor = mockProjection("ACC-POOR", id1, AccountType.ASSET);
        AccountIdProjection projRich = mockProjection("ACC-RICH", id2, AccountType.ASSET);
        when(accountRepository.findAccountIdsByAccountNumberIn(any())).thenReturn(List.of(projPoor, projRich));
        when(accountRepository.findAllByIdsWithPessimisticLock(any())).thenReturn(List.of(acc1, acc2));

        PostTransactionRequest request = PostTransactionRequest.builder()
                .transactionReference("TX-OVERDRAW")
                .transactionType(TransactionType.TRANSFER)
                .legs(List.of(
                        TransactionLegRequest.builder()
                                .accountNumber("ACC-POOR")
                                .entryType(EntryType.CREDIT)
                                .amount(new BigDecimal("100.0000"))
                                .currency("USD")
                                .build(),
                        TransactionLegRequest.builder()
                                .accountNumber("ACC-RICH")
                                .entryType(EntryType.DEBIT)
                                .amount(new BigDecimal("100.0000"))
                                .currency("USD")
                                .build()
                ))
                .build();

        assertThatThrownBy(() -> ledgerService.postTransaction(request))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("ACC-POOR");
    }

    @Test
    @DisplayName("Idempotency: Re-submitting identical idempotency key returns existing transaction directly")
    void shouldReturnExistingTransactionOnIdempotentHit() {
        Transaction existing = Transaction.builder()
                .id(UUID.randomUUID())
                .transactionReference("TX-IDEMP-HIT")
                .idempotencyKey("KEY-123")
                .transactionType(TransactionType.TRANSFER)
                .status(TransactionStatus.POSTED)
                .postedAt(Instant.now())
                .build();

        when(transactionRepository.findByIdempotencyKey("KEY-123")).thenReturn(Optional.of(existing));

        PostTransactionRequest request = PostTransactionRequest.builder()
                .transactionReference("TX-IDEMP-HIT")
                .idempotencyKey("KEY-123")
                .transactionType(TransactionType.TRANSFER)
                .legs(List.of(
                        TransactionLegRequest.builder()
                                .accountNumber("ACC-1")
                                .entryType(EntryType.CREDIT)
                                .amount(new BigDecimal("10.0000"))
                                .currency("USD")
                                .build(),
                        TransactionLegRequest.builder()
                                .accountNumber("ACC-2")
                                .entryType(EntryType.DEBIT)
                                .amount(new BigDecimal("10.0000"))
                                .currency("USD")
                                .build()
                ))
                .build();

        TransactionResponse response = ledgerService.postTransaction(request);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(existing.getId());
        assertThat(response.getTransactionReference()).isEqualTo("TX-IDEMP-HIT");

        // Verify zero repository save mutations
        verify(accountRepository, never()).saveAll(any());
        verify(transactionRepository, never()).save(any());
    }
}
