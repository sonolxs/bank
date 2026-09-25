package com.example.bank.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
        @JsonProperty("transfer_id") String transferId,
        @JsonProperty("status") String status,
        @JsonProperty("source_account_id") String sourceAccountId,
        @JsonProperty("destination_account_id") String destinationAccountId,
        @JsonProperty("amount") String amount,
        @JsonProperty("currency") String currency,
        @JsonProperty("reference") String reference,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("idempotency_key") UUID idempotencyKey
) {}