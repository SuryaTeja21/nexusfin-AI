package com.nexusfin.ledger.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusfin.ledger.domain.entity.Account;
import com.nexusfin.ledger.domain.entity.LedgerEntry;
import com.nexusfin.ledger.domain.entity.OutboxEvent;
import com.nexusfin.ledger.domain.entity.Transaction;
import com.nexusfin.ledger.domain.model.AccountStatus;
import com.nexusfin.ledger.domain.model.AccountType;
import com.nexusfin.ledger.domain.model.EntryType;
import com.nexusfin.ledger.domain.model.OutboxStatus;
import com.nexusfin.ledger.domain.model.TransactionStatus;
import com.nexusfin.ledger.domain.model.TransactionType;
import com.nexusfin.ledger.dto.*;
import com.nexusfin.ledger.exception.*;
import com.nexusfin.ledger.repository.AccountRepository;
import com.nexusfin.ledger.repository.LedgerEntryRepository;
import com.nexusfin.ledger.repository.OutboxEventRepository;
import com.nexusfin.ledger.repository.TransactionRepository;
import com.nexusfin.ledger.repository.projection.AccountIdProjection;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerService {

    private final AccountRepository accountRepository;
    private final AccountService accountService;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final RedisCacheService redisCacheService;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;

    /**
     * Posts a multi-leg double-entry transaction atomically.
     * Enforces:
     * 1. Idempotency (deduplication)
     * 2. Double-Entry math (Sum of Debits == Sum of Credits)
     * 3. Deterministic pessimistic locking in ascending ID order to prevent deadlocks
     * 4. Currency uniformity per account
     * 5. Solvency / No negative balance checks for asset/liability accounts
     */
    public TransactionResponse postTransaction(PostTransactionRequest request) {
        log.info("Processing transaction post: ref={}, idempotencyKey={}, type={}",
                request.getTransactionReference(), request.getIdempotencyKey(), request.getTransactionType());

        String idempotencyKey = request.getIdempotencyKey() != null ? request.getIdempotencyKey().trim() : null;

        // 1. Fast-path Redis idempotency cache check
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<TransactionResponse> cached = redisCacheService.getIdempotentResponse(idempotencyKey);
            if (cached.isPresent()) {
                log.info("Redis idempotency cache hit for key={}, returning cached record", idempotencyKey);
                return cached.get();
            }

            // 2. Database idempotency check (in case Redis was cold/missed)
            Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("Database idempotency hit for key={}, updating cache and returning record", idempotencyKey);
                TransactionResponse response = TransactionResponse.fromEntity(existing.get());
                redisCacheService.storeIdempotentResponse(idempotencyKey, response);
                return response;
            }

            // 3. Acquire distributed in-flight lock to block concurrent duplicate submissions
            boolean lockAcquired = redisCacheService.acquireInFlightLock(idempotencyKey);
            if (!lockAcquired) {
                log.warn("Concurrent in-flight duplicate blocked for idempotency key={}", idempotencyKey);
                throw new ConcurrentTransactionException(idempotencyKey);
            }
        }

        try {
            return transactionTemplate.execute(status -> doExecutePostTransaction(request));
        } catch (DataIntegrityViolationException ex) {
            // Under concurrent race: PostgreSQL blocks the second inserter until the first commits.
            // On unique constraint violation, the winner has already committed. Re-read once.
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                if (isIdempotencyConstraintViolation(ex)) {
                    Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
                    if (existing.isPresent()) {
                        log.info("Resolved concurrent idempotency race for key={}, returning winning record", idempotencyKey);
                        TransactionResponse response = TransactionResponse.fromEntity(existing.get());
                        redisCacheService.storeIdempotentResponse(idempotencyKey, response);
                        return response;
                    }
                }
            }
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                redisCacheService.releaseInFlightLock(idempotencyKey);
            }
            throw ex;
        } catch (Exception ex) {
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                redisCacheService.releaseInFlightLock(idempotencyKey);
            }
            throw ex;
        }
    }

    private boolean isIdempotencyConstraintViolation(DataIntegrityViolationException ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof ConstraintViolationException cve) {
                String constraint = cve.getConstraintName();
                if (constraint != null) {
                    String lower = constraint.toLowerCase();
                    if (lower.contains("idempotency") || lower.contains("transaction_reference")) {
                        return true;
                    }
                }
            }
            String msg = current.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("idempotency") || lower.contains("transaction_reference")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private TransactionResponse doExecutePostTransaction(PostTransactionRequest request) {
        // Re-check idempotency within transaction boundary
        if (request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank()) {
            Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(request.getIdempotencyKey().trim());
            if (existing.isPresent()) {
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
            if (leg.getAmount() == null || leg.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new LedgerException("Transaction leg amount must be strictly positive");
            }
            if (leg.getAmount().scale() > 4) {
                throw new LedgerException("Transaction leg amount must have at most 4 decimal places");
            }
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
        // Resolve all account IDs first via projection (avoids populating persistence context with pre-lock stale state)
        Set<String> distinctAccountNumbers = legs.stream()
                .map(l -> l.getAccountNumber().trim())
                .collect(Collectors.toSet());

        List<AccountIdProjection> idProjections = accountRepository.findAccountIdsByAccountNumberIn(distinctAccountNumbers);
        if (idProjections.size() != distinctAccountNumbers.size()) {
            Set<String> foundNumbers = idProjections.stream()
                    .map(AccountIdProjection::getAccountNumber)
                    .collect(Collectors.toSet());
            for (String accNo : distinctAccountNumbers) {
                if (!foundNumbers.contains(accNo)) {
                    throw new AccountNotFoundException(accNo);
                }
            }
        }

        List<UUID> sortedAccountIds = idProjections.stream()
                .map(AccountIdProjection::getId)
                .sorted()
                .collect(Collectors.toList());

        List<Account> lockedAccounts = accountRepository.findAllByIdsWithPessimisticLock(sortedAccountIds);
        if (lockedAccounts.size() != sortedAccountIds.size()) {
            throw new AccountNotFoundException("One or more accounts could not be locked");
        }

        // Refresh accounts to ensure latest database state if previously in session
        for (Account a : lockedAccounts) {
            entityManager.refresh(a);
        }

        Map<String, Account> lockedAccountMap = lockedAccounts.stream()
                .collect(Collectors.toMap(Account::getAccountNumber, Function.identity()));

        // 4. Validate accounts state, currencies, and multi-tenant caller ownership
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isCustomer = auth != null && auth.isAuthenticated() && !accountService.isSystemOrAdmin(auth);

        for (TransactionLegRequest leg : legs) {
            Account account = lockedAccountMap.get(leg.getAccountNumber().trim());
            if (account.getStatus() != AccountStatus.ACTIVE) {
                throw new InvalidAccountStateException(account.getAccountNumber(), account.getStatus());
            }
            if (leg.getCurrency() != null && !account.getCurrency().equalsIgnoreCase(leg.getCurrency().trim())) {
                throw new CurrencyMismatchException(account.getCurrency(), leg.getCurrency(), account.getAccountNumber());
            }

            // A customer can only decrease balances on accounts they own
            if (isCustomer) {
                boolean decreasesBalance = (account.getAccountType().getNormalBalance() == EntryType.DEBIT && leg.getEntryType() == EntryType.CREDIT)
                        || (account.getAccountType().getNormalBalance() == EntryType.CREDIT && leg.getEntryType() == EntryType.DEBIT);
                if (decreasesBalance) {
                    accountService.verifyAccountOwnership(account, auth);
                }
            }
        }

        // 5. Build Transaction & Ledger Entries
        TransactionType transactionType = request.getTransactionType() != null
                ? request.getTransactionType()
                : TransactionType.TRANSFER;

        Transaction transaction = Transaction.builder()
                .transactionReference(request.getTransactionReference().trim())
                .idempotencyKey(request.getIdempotencyKey() != null ? request.getIdempotencyKey().trim() : null)
                .transactionType(transactionType)
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
        Transaction saved = transactionRepository.saveAndFlush(transaction);

        // Record Transactional Outbox Event for reliable Kafka streaming
        recordOutboxEvent(saved, legs, lockedAccountMap, totalDebits);

        log.info("Transaction posted successfully: id={}, ref={}, totalVolume={}",
                saved.getId(), saved.getTransactionReference(), totalDebits);

        TransactionResponse response = TransactionResponse.fromEntity(saved);

        // Register post-commit cache synchronization (evict stale accounts & persist idempotency cache)
        Set<String> affectedAccounts = new HashSet<>(distinctAccountNumbers);
        String finalIdemKey = request.getIdempotencyKey() != null ? request.getIdempotencyKey().trim() : null;

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    redisCacheService.evictAccounts(affectedAccounts);
                    if (finalIdemKey != null && !finalIdemKey.isBlank()) {
                        redisCacheService.storeIdempotentResponse(finalIdemKey, response);
                    }
                }

                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED && finalIdemKey != null && !finalIdemKey.isBlank()) {
                        redisCacheService.releaseInFlightLock(finalIdemKey);
                    }
                }
            });
        } else {
            redisCacheService.evictAccounts(affectedAccounts);
            if (finalIdemKey != null && !finalIdemKey.isBlank()) {
                redisCacheService.storeIdempotentResponse(finalIdemKey, response);
            }
        }

        return response;
    }

    /**
     * High-level fund transfer between two accounts using TransferFundsRequest.
     */
    public TransactionResponse transferFunds(TransferFundsRequest request) {
        log.info("Initiating fund transfer: source={}, dest={}, amount={} {}",
                request.getSourceAccountNumber(), request.getDestinationAccountNumber(), request.getAmount(), request.getCurrency());

        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new LedgerException("Transfer amount must be strictly positive");
        }

        if (request.getAmount().scale() > 4) {
            throw new LedgerException("Transfer amount must have at most 4 decimal places");
        }

        if (request.getSourceAccountNumber().trim().equalsIgnoreCase(request.getDestinationAccountNumber().trim())) {
            throw new LedgerException("Source and destination accounts cannot be identical");
        }

        List<AccountIdProjection> projections = accountRepository.findAccountIdsByAccountNumberIn(
                List.of(request.getSourceAccountNumber().trim(), request.getDestinationAccountNumber().trim()));

        Map<String, AccountIdProjection> projectionMap = projections.stream()
                .collect(Collectors.toMap(AccountIdProjection::getAccountNumber, Function.identity()));

        AccountIdProjection sourceProj = projectionMap.get(request.getSourceAccountNumber().trim());
        if (sourceProj == null) {
            throw new AccountNotFoundException(request.getSourceAccountNumber());
        }
        AccountIdProjection destProj = projectionMap.get(request.getDestinationAccountNumber().trim());
        if (destProj == null) {
            throw new AccountNotFoundException(request.getDestinationAccountNumber());
        }

        EntryType sourceEntryType = sourceProj.getAccountType().getNormalBalance() == EntryType.DEBIT
                ? EntryType.CREDIT
                : EntryType.DEBIT;

        EntryType destEntryType = sourceEntryType == EntryType.DEBIT
                ? EntryType.CREDIT
                : EntryType.DEBIT;

        String ref = request.getTransactionReference() != null && !request.getTransactionReference().isBlank()
                ? request.getTransactionReference().trim()
                : "TRF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        PostTransactionRequest postRequest = PostTransactionRequest.builder()
                .transactionReference(ref)
                .idempotencyKey(request.getIdempotencyKey())
                .transactionType(TransactionType.TRANSFER)
                .description(request.getDescription() != null ? request.getDescription() : "Transfer between accounts")
                .legs(List.of(
                        TransactionLegRequest.builder()
                                .accountNumber(request.getSourceAccountNumber().trim())
                                .entryType(sourceEntryType)
                                .amount(request.getAmount())
                                .currency(request.getCurrency())
                                .build(),
                        TransactionLegRequest.builder()
                                .accountNumber(request.getDestinationAccountNumber().trim())
                                .entryType(destEntryType)
                                .amount(request.getAmount())
                                .currency(request.getCurrency())
                                .build()
                ))
                .build();

        return postTransaction(postRequest);
    }

    /**
     * Overloaded fund transfer supporting TransferRequest (by UUID Account IDs).
     */
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

        return transferFunds(TransferFundsRequest.builder()
                .sourceAccountNumber(sourceAccount.getAccountNumber())
                .destinationAccountNumber(destAccount.getAccountNumber())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .idempotencyKey(request.getIdempotencyKey())
                .transactionReference(request.getTransactionReference())
                .description(request.getDescription())
                .build());
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public TransactionResponse getTransactionByReference(String reference) {
        return transactionRepository.findByTransactionReference(reference.trim())
                .map(TransactionResponse::fromEntity)
                .orElseThrow(() -> new LedgerException("Transaction not found for reference: " + reference));
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<LedgerEntryResponse> getEntriesByAccountId(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        accountService.verifyAccountOwnership(account, SecurityContextHolder.getContext().getAuthentication());

        return ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(accountId).stream()
                .map(LedgerEntryResponse::fromEntity)
                .collect(Collectors.toList());
    }

    private void recordOutboxEvent(Transaction transaction, List<TransactionLegRequest> legs,
                                    Map<String, Account> lockedAccountMap, BigDecimal totalVolume) {
        try {
            Map<String, Object> eventPayload = new HashMap<>();
            eventPayload.put("transactionId", transaction.getId());
            eventPayload.put("transactionReference", transaction.getTransactionReference());
            eventPayload.put("transactionType", transaction.getTransactionType().name());
            eventPayload.put("totalVolume", totalVolume);
            eventPayload.put("postedAt", transaction.getPostedAt().toString());

            List<Map<String, Object>> legSummaries = legs.stream().map(leg -> {
                Map<String, Object> map = new HashMap<>();
                Account acc = lockedAccountMap.get(leg.getAccountNumber().trim());
                map.put("accountId", acc.getId());
                map.put("accountNumber", acc.getAccountNumber());
                map.put("accountType", acc.getAccountType().name());
                map.put("entryType", leg.getEntryType().name());
                map.put("amount", leg.getAmount());
                map.put("currency", leg.getCurrency());
                map.put("newBalance", acc.getBalance());
                return map;
            }).collect(Collectors.toList());

            eventPayload.put("legs", legSummaries);

            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .aggregateType("TRANSACTION")
                    .aggregateId(transaction.getId().toString())
                    .eventType("TRANSACTION_POSTED")
                    .payload(objectMapper.writeValueAsString(eventPayload))
                    .status(OutboxStatus.PENDING)
                    .retryCount(0)
                    .build();

            outboxEventRepository.save(outboxEvent);
        } catch (Exception e) {
            log.error("Failed to serialize and record outbox event for transaction [{}]: {}",
                    transaction.getId(), e.getMessage(), e);
            throw new LedgerException("Failed to record outbox event: " + e.getMessage());
        }
    }
}
