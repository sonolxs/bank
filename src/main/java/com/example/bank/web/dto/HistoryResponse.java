package com.example.bank.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record HistoryResponse(
        @JsonProperty("account_id") String accountId,
        @JsonProperty("items") List<HistoryItemResponse> items,
        @JsonProperty("limit") int limit,
        @JsonProperty("offset") int offset,
        @JsonProperty("total") long total
) {}