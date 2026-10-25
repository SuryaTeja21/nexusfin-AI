package com.nexusfin.ledger.service;

import com.nexusfin.ledger.domain.entity.Account;
import com.nexusfin.ledger.domain.entity.LedgerEntry;
import com.nexusfin.ledger.domain.entity.Transaction;
import com.nexusfin.ledger.domain.model.AccountStatus;
import com.nexusfin.ledger.domain.model.AccountType;
import com.nexusfin.ledger.domain.model.EntryType;
import com.nexusfin.ledger.domain.model.TransactionStatus;
import com.nexusfin.ledger.domain.model.TransactionType;
import com.nexusfin.ledger.dto.*;
import com.nexusfin.ledger.exception.*;
import com.nexusfin.ledger.repository.AccountRepository;
import com.nexusfin.ledger.repository.LedgerEntryRepository;
import com.nexusfin.ledger.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    /**
     * Posts a multi-leg double-entry transaction atomically.
     * Enforces:
     * 1. Idempotency (deduplication)
     * 2. Double-Entry math (Sum of Debits == Sum of Credits)
     * 3. Deterministic pessimistic locking to prevent deadlocks
     * 4. Currency uniformity per account
     * 5. Solvency / No negative balance checks for asset/liability accounts
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransactionResponse postTransaction(PostTransactionRequest request) {
        log.info("Processing transaction post: ref={}, idempotencyKey={}, type={}",
                request.getTransactionReference(), request.getIdempotencyKey(), request.getTransactionType());

        // 1. Idempotency verification
        if (request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank()) {
            Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(request.getIdempotencyKey());
            if (existing.isPresent()) {
                log.info("Idempotent hit for transaction ref={}, returning existing record", existing.get().getTransactionReference());
                return TransactionResponse.fromEntity(existing.get());
            }
        }

        // Harmonize legs and entries input
        List<TransactionLegRequest> legs = new ArrayList<>();
        if (request.getLegs() != null && !request.getLegs().isEmpty()) {
            legs.addAll(request.getLegs());
        } else if (request.getEntries() != null && !request.getEntries().isEmpty()) {
            for (EntryRequest entryReq : request.getEntries()) {
                Account acc = accountRepository.findById(entryReq.getAccountId())
                        .orElseThrow(() -> new AccountNotFoundException(entryReq.getAccountId()));
                legs.add(TransactionLegRequest.builder()
                        .accountNumber(acc.getAccountNumber())
                        .entryType(entryReq.getEntryType())
                        .amount(entryReq.getAmount())
                        .currency(entryReq.getCurrency())
                        .build());
            }
        }

        if (legs.size() < 2) {
            throw new LedgerException("Double-entry transaction must contain at least 2 legs");
        }

        // 2. Double-Entry balance validation
        BigDecimal totalDebits = BigDecimal.ZERO.setScale(4);
        BigDecimal totalCredits = BigDecimal.ZERO.setScale(4);

        for (TransactionLegRequest leg : legs) {
            BigDecimal scaledAmount = leg.getAmount().setScale(4);
            if (leg.getEntryType() == EntryType.DEBIT) {
                totalDebits = totalDebits.add(scaledAmount);
            } else if (leg.getEntryType() == EntryType.CREDIT) {
                totalCredits = totalCredits.add(scaledAmount);
            }
        }

        if (totalDebits.compareTo(totalCredits) != 0) {
            throw new UnbalancedTransactionException(totalDebits, totalCredits);
        }

        // 3. Deterministic pessimistic lock acquisition to guarantee DEADLOCK IMMUNITY
        // Sort unique account numbers lexicographically before acquiring row locks
        List<String> sortedAccountNumbers = legs.stream()
                .map(l -> l.getAccountNumber().trim())
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        Map<String, Account> lockedAccountMap = new HashMap<>();
        for (String accNo : sortedAccountNumbers) {
            Account account = accountRepository.findByAccountNumberWithLock(accNo)
                    .orElseThrow(() -> new AccountNotFoundException(accNo));
            lockedAccountMap.put(accNo, account);
        }

        // 4. Validate accounts state and currencies
        for (TransactionLegRequest leg : legs) {
            Account account = lockedAccountMap.get(leg.getAccountNumber().trim());
            if (account.getStatus() != AccountStatus.ACTIVE) {
                throw new InvalidAccountStateException(account.getAccountNumber(), account.getStatus());
            }
            if (!account.getCurrency().equalsIgnoreCase(leg.getCurrency().trim())) {
                throw new CurrencyMismatchException(account.getCurrency(), leg.getCurrency(), account.getAccountNumber());
            }
        }

        // 5. Build Transaction & Ledger Entries
        Transaction transaction = Transaction.builder()
                .transactionReference(request.getTransactionReference().trim())
                .idempotencyKey(request.getIdempotencyKey() != null ? request.getIdempotencyKey().trim() : null)
                .transactionType(request.getTransactionType())
                .description(request.getDescription())
                .status(TransactionStatus.POSTED)
                .postedAt(Instant.now())
                .build();

        int sequenceNumber = 1;
        for (TransactionLegRequest leg : legs) {
            Account account = lockedAccountMap.get(leg.getAccountNumber().trim());
            BigDecimal amount = leg.getAmount().setScale(4);
            BigDecimal newBalance = account.getAccountType().calculateNewBalance(account.getBalance(), leg.getEntryType(), amount);

            // Invariant check: Assets and customer deposits cannot be overdrawn below zero
            if (newBalance.compareTo(BigDecimal.ZERO) < 0 &&
                    (account.getAccountType() == AccountType.ASSET || account.getAccountType() == AccountType.LIABILITY)) {
                throw new InsufficientFundsException(account.getAccountNumber(), account.getBalance(), amount);
            }

            account.setBalance(newBalance);

            LedgerEntry entry = LedgerEntry.builder()
                    .account(account)
                    .entryType(leg.getEntryType())
                    .amount(amount)
                    .currency(account.getCurrency())
                    .balanceAfter(newBalance)
                    .sequenceNumber(sequenceNumber++)
                    .build();

            transaction.addEntry(entry);
        }

        // Save accounts and transaction
        accountRepository.saveAll(lockedAccountMap.values());
        Transaction saved = transactionRepository.save(transaction);

        log.info("Transaction posted successfully: id={}, ref={}, totalVolume={}",
                saved.getId(), saved.getTransactionReference(), totalDebits);

        return TransactionResponse.fromEntity(saved);
    }

    /**
     * High-level fund transfer between two accounts using TransferFundsRequest.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransactionResponse transferFunds(TransferFundsRequest request) {
        log.info("Initiating fund transfer: source={}, dest={}, amount={} {}",
                request.getSourceAccountNumber(), request.getDestinationAccountNumber(), request.getAmount(), request.getCurrency());

        if (request.getSourceAccountNumber().trim().equalsIgnoreCase(request.getDestinationAccountNumber().trim())) {
            throw new LedgerException("Source and destination accounts cannot be identical");
        }

        Account sourceAccount = accountRepository.findByAccountNumber(request.getSourceAccountNumber().trim())
                .orElseThrow(() -> new AccountNotFoundException(request.getSourceAccountNumber()));
        Account destAccount = accountRepository.findByAccountNumber(request.getDestinationAccountNumber().trim())
                .orElseThrow(() -> new AccountNotFoundException(request.getDestinationAccountNumber()));

        return executeTransfer(sourceAccount, destAccount, request.getAmount(), request.getCurrency(),
                request.getIdempotencyKey(), request.getTransactionReference(), request.getDescription());
    }

    /**
     * Overloaded fund transfer supporting TransferRequest (by UUID Account IDs).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransactionResponse transferFunds(TransferRequest request) {
        log.info("Initiating fund transfer by ID: source={}, dest={}, amount={} {}",
                request.getSourceAccountId(), request.getTargetAccountId(), request.getAmount(), request.getCurrency());

        if (request.getSourceAccountId().equals(request.getTargetAccountId())) {
            throw new LedgerException("Source and destination accounts cannot be identical");
        }

        Account sourceAccount = accountRepository.findById(request.getSourceAccountId())
                .orElseThrow(() -> new AccountNotFoundException(request.getSourceAccountId()));
        Account destAccount = accountRepository.findById(request.getTargetAccountId())
                .orElseThrow(() -> new AccountNotFoundException(request.getTargetAccountId()));

        return executeTransfer(sourceAccount, destAccount, request.getAmount(), request.getCurrency(),
                request.getIdempotencyKey(), request.getTransactionReference(), request.getDescription());
    }

    private TransactionResponse executeTransfer(Account sourceAccount, Account destAccount, BigDecimal amount,
                                                 String currency, String idempotencyKey, String transactionReference,
                                                 String description) {
        // Source account decreases (opposite of normal balance)
        EntryType sourceEntryType = sourceAccount.getAccountType().getNormalBalance() == EntryType.DEBIT
                ? EntryType.CREDIT
                : EntryType.DEBIT;

        // Destination account increases (its normal balance)
        EntryType destEntryType = destAccount.getAccountType().getNormalBalance();

        String ref = transactionReference != null && !transactionReference.isBlank()
                ? transactionReference
                : "TRF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        PostTransactionRequest postRequest = PostTransactionRequest.builder()
                .transactionReference(ref)
                .idempotencyKey(idempotencyKey)
                .transactionType(TransactionType.TRANSFER)
                .description(description != null ? description : "Transfer between accounts")
                .legs(List.of(
                        TransactionLegRequest.builder()
                                .accountNumber(sourceAccount.getAccountNumber())
                                .entryType(sourceEntryType)
                                .amount(amount)
                                .currency(currency)
                                .build(),
                        TransactionLegRequest.builder()
                                .accountNumber(destAccount.getAccountNumber())
                                .entryType(destEntryType)
                                .amount(amount)
                                .currency(currency)
                                .build()
                ))
                .build();

        return postTransaction(postRequest);
    }

    @Transactional(readOnly = true)
    public TransactionResponse getTransactionByReference(String reference) {
        return transactionRepository.findByTransactionReference(reference.trim())
                .map(TransactionResponse::fromEntity)
                .orElseThrow(() -> new LedgerException("Transaction not found for reference: " + reference));
    }

    @Transactional(readOnly = true)
    public List<LedgerEntryResponse> getEntriesByAccountId(UUID accountId) {
        return ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(accountId).stream()
                .map(LedgerEntryResponse::fromEntity)
                .collect(Collectors.toList());
    }
}
