package com.example.bank.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record HistoryItemResponse(
        @JsonProperty("transfer_id") String transferId,
        @JsonProperty("direction") String direction,
        @JsonProperty("amount") String amount,
        @JsonProperty("currency") String currency,
        @JsonProperty("counterparty_account_id") String counterpartyAccountId,
        @JsonProperty("status") String status,
        @JsonProperty("created_at") Instant createdAt
) {}