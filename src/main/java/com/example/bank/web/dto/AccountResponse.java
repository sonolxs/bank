package com.example.bank.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AccountResponse(
        @JsonProperty("account_id") String accountId,
        @JsonProperty("currency") String currency,
        @JsonProperty("balance") String balance,
        @JsonProperty("status") String status
) {}