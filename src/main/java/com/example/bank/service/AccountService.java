package com.example.bank.service;

import com.example.bank.domain.Account;
import com.example.bank.error.DomainException;
import com.example.bank.error.ErrorCode;
import com.example.bank.repository.AccountRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public Account getById(String accountId) {
        return accountRepository.findByIdReadOnly(accountId)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.ACCOUNT_NOT_FOUND,
                        HttpStatus.NOT_FOUND,
                        "Account not found: " + accountId
                ));
    }
}