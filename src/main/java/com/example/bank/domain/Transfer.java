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

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transfers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transfer {

    @Id
    @Column(name = "transfer_id", nullable = false, updatable = false)
    private String transferId;

    @Column(name = "idempotency_key", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID idempotencyKey;

    @Column(name = "source_account_id", nullable = false, updatable = false)
    private String sourceAccountId;

    @Column(name = "destination_account_id", nullable = false, updatable = false)
    private String destinationAccountId;

    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private Currency currency;

    @Column(name = "reference", length = 140, updatable = false)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TransferStatus status;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public Transfer(String transferId,
                    UUID idempotencyKey,
                    String sourceAccountId,
                    String destinationAccountId,
                    long amount,
                    Currency currency,
                    String reference,
                    Instant createdAt) {
        this.transferId = transferId;
        this.idempotencyKey = idempotencyKey;
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.amount = amount;
        this.currency = currency;
        this.reference = reference;
        this.status = TransferStatus.PENDING;
        this.createdAt = createdAt;
    }

    public void complete(Instant completedAt) {
        if (this.status != TransferStatus.PENDING) {
            throw new IllegalStateException("only PENDING transfers can complete");
        }
        this.status = TransferStatus.COMPLETED;
        this.completedAt = completedAt;
    }

    public void fail(String failureCode, Instant completedAt) {
        if (this.status != TransferStatus.PENDING) {
            throw new IllegalStateException("only PENDING transfers can fail");
        }
        this.status = TransferStatus.FAILED;
        this.failureCode = failureCode;
        this.completedAt = completedAt;
    }

    public boolean matchesRequest(String sourceAccountId,
                                  String destinationAccountId,
                                  long amount,
                                  Currency currency,
                                  String reference) {
        return this.sourceAccountId.equals(sourceAccountId)
                && this.destinationAccountId.equals(destinationAccountId)
                && this.amount == amount
                && this.currency == currency
                && java.util.Objects.equals(this.reference, reference);
    }
}