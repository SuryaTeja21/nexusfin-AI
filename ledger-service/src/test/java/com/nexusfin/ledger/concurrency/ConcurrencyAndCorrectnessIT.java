package com.nexusfin.ledger.concurrency;

import com.nexusfin.ledger.AbstractPostgresIT;
import com.nexusfin.ledger.domain.entity.Account;
import com.nexusfin.ledger.domain.entity.Transaction;
import com.nexusfin.ledger.domain.model.AccountStatus;
import com.nexusfin.ledger.domain.model.AccountType;
import com.nexusfin.ledger.dto.TransactionResponse;
import com.nexusfin.ledger.dto.TransferFundsRequest;
import com.nexusfin.ledger.exception.InsufficientFundsException;
import com.nexusfin.ledger.repository.AccountRepository;
import com.nexusfin.ledger.repository.TransactionRepository;
import com.nexusfin.ledger.service.LedgerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {"nexusfin.kafka.outbox.enabled=false"})
class ConcurrencyAndCorrectnessIT extends AbstractPostgresIT {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    private Account createAccount(String prefix, BigDecimal initialBalance) {
        UUID id = UUID.randomUUID();
        Account account = Account.builder()
                .id(id)
                .accountNumber(prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .accountName("Account " + prefix)
                .accountType(AccountType.ASSET)
                .currency("USD")
                .balance(initialBalance.setScale(4))
                .status(AccountStatus.ACTIVE)
                .build();
        return accountRepository.save(account);
    }

    @Test
    @DisplayName("1.3.1 Opposing transfers, no deadlock: 100 concurrent transfers (50 A->B, 50 B->A)")
    void opposingTransfers_mustNotDeadlockAndConserveBalances() throws Exception {
        Account accountA = createAccount("ACC-OPP-A", new BigDecimal("1000.0000"));
        Account accountB = createAccount("ACC-OPP-B", new BigDecimal("1000.0000"));

        int totalTransfers = 100;
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(totalTransfers);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < totalTransfers; i++) {
            boolean aToB = (i % 2 == 0);
            String src = aToB ? accountA.getAccountNumber() : accountB.getAccountNumber();
            String dst = aToB ? accountB.getAccountNumber() : accountA.getAccountNumber();

            executor.submit(() -> {
                try {
                    startGate.await();
                    ledgerService.transferFunds(TransferFundsRequest.builder()
                            .sourceAccountNumber(src)
                            .destinationAccountNumber(dst)
                            .amount(new BigDecimal("10.0000"))
                            .currency("USD")
                            .description("Opposing transfer concurrent test")
                            .build());
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean completed = doneGate.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).withFailMessage("Timed out waiting for opposing transfers to complete").isTrue();
        assertThat(errors).withFailMessage("Encountered errors during opposing transfers: %s", errors).isEmpty();

        Account finalA = accountRepository.findById(accountA.getId()).orElseThrow();
        Account finalB = accountRepository.findById(accountB.getId()).orElseThrow();

        assertThat(finalA.getBalance()).isEqualByComparingTo(new BigDecimal("1000.0000"));
        assertThat(finalB.getBalance()).isEqualByComparingTo(new BigDecimal("1000.0000"));
        assertThat(finalA.getBalance().add(finalB.getBalance()))
                .isEqualByComparingTo(new BigDecimal("2000.0000"));
    }

    @Test
    @DisplayName("1.3.2 Many accounts, random pairs: 20 accounts, 500 random transfers, total conserved")
    void manyAccountsRandomTransfers_mustConserveTotalAndNeverOverdraw() throws Exception {
        int accountCount = 20;
        List<Account> accounts = new ArrayList<>();
        BigDecimal initialBalancePerAccount = new BigDecimal("1000.0000");
        BigDecimal expectedTotal = initialBalancePerAccount.multiply(new BigDecimal(accountCount));

        for (int i = 0; i < accountCount; i++) {
            accounts.add(createAccount("ACC-RND-" + i, initialBalancePerAccount));
        }

        int totalTransfers = 500;
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(totalTransfers);
        ConcurrentLinkedQueue<Throwable> unexpectedErrors = new ConcurrentLinkedQueue<>();
        AtomicInteger rejectedInsufficientFunds = new AtomicInteger(0);

        Random random = new Random(42);
        for (int i = 0; i < totalTransfers; i++) {
            int srcIdx = random.nextInt(accountCount);
            int dstIdx = random.nextInt(accountCount);
            while (dstIdx == srcIdx) {
                dstIdx = random.nextInt(accountCount);
            }

            String src = accounts.get(srcIdx).getAccountNumber();
            String dst = accounts.get(dstIdx).getAccountNumber();
            BigDecimal amount = new BigDecimal(random.nextInt(25) + 1).setScale(4);

            executor.submit(() -> {
                try {
                    startGate.await();
                    ledgerService.transferFunds(TransferFundsRequest.builder()
                            .sourceAccountNumber(src)
                            .destinationAccountNumber(dst)
                            .amount(amount)
                            .currency("USD")
                            .description("Random pair transfer test")
                            .build());
                } catch (InsufficientFundsException e) {
                    rejectedInsufficientFunds.incrementAndGet();
                } catch (Throwable t) {
                    unexpectedErrors.add(t);
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean completed = doneGate.await(45, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).withFailMessage("Timed out waiting for 500 random transfers").isTrue();
        assertThat(unexpectedErrors).withFailMessage("Unexpected errors occurred during transfers: %s", unexpectedErrors).isEmpty();

        BigDecimal actualTotal = BigDecimal.ZERO.setScale(4);
        for (Account acc : accounts) {
            Account updated = accountRepository.findById(acc.getId()).orElseThrow();
            assertThat(updated.getBalance().compareTo(BigDecimal.ZERO))
                    .withFailMessage("Account %s was overdrawn below zero: %s", updated.getAccountNumber(), updated.getBalance())
                    .isGreaterThanOrEqualTo(0);
            actualTotal = actualTotal.add(updated.getBalance());
        }

        assertThat(actualTotal)
                .withFailMessage("Double-entry conservation violated! Expected total %s, but got %s", expectedTotal, actualTotal)
                .isEqualByComparingTo(expectedTotal);
    }

    @Test
    @DisplayName("1.3.3 Idempotency under race: 50 threads submit identical idempotencyKey simultaneously")
    void idempotencyUnderRace_allThreadsReceiveSameTransactionIdAndSingleRowPersisted() throws Exception {
        Account accountA = createAccount("ACC-IDEMP-A", new BigDecimal("1000.0000"));
        Account accountB = createAccount("ACC-IDEMP-B", new BigDecimal("1000.0000"));

        String sharedIdempotencyKey = "IDEMP-RACE-" + UUID.randomUUID();
        String sharedTxRef = "TX-IDEMP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        int threadCount = 50;

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threadCount);
        ConcurrentLinkedQueue<TransactionResponse> responses = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    TransactionResponse res = ledgerService.transferFunds(TransferFundsRequest.builder()
                            .sourceAccountNumber(accountA.getAccountNumber())
                            .destinationAccountNumber(accountB.getAccountNumber())
                            .amount(new BigDecimal("50.0000"))
                            .currency("USD")
                            .idempotencyKey(sharedIdempotencyKey)
                            .transactionReference(sharedTxRef)
                            .description("Concurrent idempotency test")
                            .build());
                    responses.add(res);
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean completed = doneGate.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).withFailMessage("Timed out waiting for concurrent idempotency submissions").isTrue();
        assertThat(errors).withFailMessage("Concurrent idempotency failed with errors: %s", errors).isEmpty();
        assertThat(responses).hasSize(threadCount);

        // Verify all 50 threads received the exact same transaction ID
        UUID firstTxId = responses.peek().getId();
        assertThat(firstTxId).isNotNull();
        for (TransactionResponse resp : responses) {
            assertThat(resp.getId()).isEqualTo(firstTxId);
            assertThat(resp.getTransactionReference()).isEqualTo(sharedTxRef);
        }

        // Verify exactly one transaction row exists in database for this idempotency key
        Optional<Transaction> dbTx = transactionRepository.findByIdempotencyKey(sharedIdempotencyKey);
        assertThat(dbTx).isPresent();
        assertThat(dbTx.get().getId()).isEqualTo(firstTxId);

        // Verify balances mutated exactly once
        Account finalA = accountRepository.findById(accountA.getId()).orElseThrow();
        Account finalB = accountRepository.findById(accountB.getId()).orElseThrow();
        assertThat(finalA.getBalance()).isEqualByComparingTo(new BigDecimal("950.0000"));
        assertThat(finalB.getBalance()).isEqualByComparingTo(new BigDecimal("1050.0000"));
    }

    @Test
    @DisplayName("1.3.4 Insufficient funds under race: balance 100, 20 threads debit 10 -> exactly 10 succeed")
    void insufficientFundsUnderRace_exactlySolventTransfersSucceed() throws Exception {
        Account accountA = createAccount("ACC-SOLV-A", new BigDecimal("100.0000"));
        Account accountB = createAccount("ACC-SOLV-B", new BigDecimal("0.0000"));

        int threadCount = 20;
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger insufficientFundsCount = new AtomicInteger(0);
        ConcurrentLinkedQueue<Throwable> unexpectedErrors = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    ledgerService.transferFunds(TransferFundsRequest.builder()
                            .sourceAccountNumber(accountA.getAccountNumber())
                            .destinationAccountNumber(accountB.getAccountNumber())
                            .amount(new BigDecimal("10.0000"))
                            .currency("USD")
                            .description("Solvency race test")
                            .build());
                    successCount.incrementAndGet();
                } catch (InsufficientFundsException e) {
                    insufficientFundsCount.incrementAndGet();
                } catch (Throwable t) {
                    unexpectedErrors.add(t);
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean completed = doneGate.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).withFailMessage("Timed out waiting for solvency race").isTrue();
        assertThat(unexpectedErrors).withFailMessage("Unexpected errors during solvency race: %s", unexpectedErrors).isEmpty();
        assertThat(successCount.get())
                .withFailMessage("Expected exactly 10 transfers to succeed but was %d", successCount.get())
                .isEqualTo(10);
        assertThat(insufficientFundsCount.get())
                .withFailMessage("Expected exactly 10 transfers to fail with InsufficientFundsException but was %d", insufficientFundsCount.get())
                .isEqualTo(10);

        Account finalA = accountRepository.findById(accountA.getId()).orElseThrow();
        Account finalB = accountRepository.findById(accountB.getId()).orElseThrow();

        assertThat(finalA.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(finalB.getBalance()).isEqualByComparingTo(new BigDecimal("100.0000"));
    }
}
