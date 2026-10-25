package com.nexusfin.ledger.service;

import com.nexusfin.ledger.domain.entity.Account;
import com.nexusfin.ledger.domain.model.AccountStatus;
import com.nexusfin.ledger.dto.AccountResponse;
import com.nexusfin.ledger.dto.CreateAccountRequest;
import com.nexusfin.ledger.exception.AccountNotFoundException;
import com.nexusfin.ledger.exception.LedgerException;
import com.nexusfin.ledger.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;

    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        if (accountRepository.existsByAccountNumber(request.getAccountNumber())) {
            throw new LedgerException("Account number already exists: " + request.getAccountNumber());
        }

        BigDecimal balance = request.getInitialBalance() != null
                ? request.getInitialBalance().setScale(4)
                : BigDecimal.ZERO.setScale(4);

        Account account = Account.builder()
                .accountNumber(request.getAccountNumber().trim())
                .accountName(request.getAccountName().trim())
                .accountType(request.getAccountType())
                .currency(request.getCurrency().toUpperCase().trim())
                .balance(balance)
                .status(AccountStatus.ACTIVE)
                .build();

        Account saved = accountRepository.save(account);
        log.info("Created new account: id={}, number={}, type={}, currency={}",
                saved.getId(), saved.getAccountNumber(), saved.getAccountType(), saved.getCurrency());

        return AccountResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccountById(UUID id) {
        return accountRepository.findById(id)
                .map(AccountResponse::fromEntity)
                .orElseThrow(() -> new AccountNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccountByAccountNumber(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber.trim())
                .map(AccountResponse::fromEntity)
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> getAllAccounts() {
        return accountRepository.findAll().stream()
                .map(AccountResponse::fromEntity)
                .collect(Collectors.toList());
    }
}
