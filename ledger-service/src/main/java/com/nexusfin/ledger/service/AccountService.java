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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
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
                .ownerId(request.getOwnerId() != null && !request.getOwnerId().isBlank() ? request.getOwnerId().trim() : null)
                .build();

        Account saved = accountRepository.save(account);
        log.info("Created new account: id={}, number={}, type={}, currency={}, ownerId={}",
                saved.getId(), saved.getAccountNumber(), saved.getAccountType(), saved.getCurrency(), saved.getOwnerId());

        return AccountResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccountById(UUID id) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));
        verifyAccountOwnership(account, SecurityContextHolder.getContext().getAuthentication());
        return AccountResponse.fromEntity(account);
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccountByAccountNumber(String accountNumber) {
        Account account = accountRepository.findByAccountNumber(accountNumber.trim())
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));
        verifyAccountOwnership(account, SecurityContextHolder.getContext().getAuthentication());
        return AccountResponse.fromEntity(account);
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> getAllAccounts() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !isSystemOrAdmin(auth)) {
            String principalId = getPrincipalId(auth);
            return accountRepository.findByOwnerId(principalId).stream()
                    .map(AccountResponse::fromEntity)
                    .collect(Collectors.toList());
        }
        return accountRepository.findAll().stream()
                .map(AccountResponse::fromEntity)
                .collect(Collectors.toList());
    }

    public void verifyAccountOwnership(Account account, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return;
        }
        if (isSystemOrAdmin(authentication)) {
            return;
        }

        String callerId = getPrincipalId(authentication);
        if (account.getOwnerId() != null && !account.getOwnerId().equalsIgnoreCase(callerId)) {
            log.warn("Access denied: user [{}] attempted to access account [{}] owned by [{}]",
                    callerId, account.getAccountNumber(), account.getOwnerId());
            throw new AccessDeniedException("Access denied: account not owned by authenticated user");
        }
    }

    public boolean isSystemOrAdmin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a ->
                "ROLE_ADMIN".equalsIgnoreCase(a.getAuthority()) ||
                "ROLE_SERVICE".equalsIgnoreCase(a.getAuthority()));
    }

    public String getPrincipalId(Authentication auth) {
        if (auth.getPrincipal() instanceof Jwt jwt) {
            if (jwt.getSubject() != null && !jwt.getSubject().isBlank()) {
                return jwt.getSubject();
            }
        }
        return auth.getName();
    }
}
