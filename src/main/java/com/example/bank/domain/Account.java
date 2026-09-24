package com.example.bank.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

    @Id
    @Column(name = "account_id", nullable = false, updatable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3)
    private Currency currency;

    /**
     * Saldo en centavos. Unidad mínima. Nunca float/double.
     */
    @Column(name = "balance", nullable = false)
    private long balance;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AccountStatus status;

    public Account(String accountId, Currency currency, long balance, AccountStatus status) {
        this.accountId = accountId;
        this.currency = currency;
        this.balance = balance;
        this.status = status;
    }

    public void debit(long amountInCents) {
        if (amountInCents <= 0) {
            throw new IllegalArgumentException("debit amount must be positive");
        }
        if (this.balance < amountInCents) {
            throw new IllegalStateException("insufficient balance");
        }
        this.balance -= amountInCents;
    }

    public void credit(long amountInCents) {
        if (amountInCents <= 0) {
            throw new IllegalArgumentException("credit amount must be positive");
        }
        this.balance += amountInCents;
    }
}