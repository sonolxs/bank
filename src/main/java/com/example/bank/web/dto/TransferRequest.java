package com.example.bank.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = false)
public record TransferRequest(
        @JsonProperty("source_account_id")
        @NotBlank(message = "source_account_id is required")
        String sourceAccountId,

        @JsonProperty("destination_account_id")
        @NotBlank(message = "destination_account_id is required")
        String destinationAccountId,

        @JsonProperty("amount")
        @NotBlank(message = "amount is required")
        String amount,

        @JsonProperty("currency")
        @NotBlank(message = "currency is required")
        String currency,

        @JsonProperty("reference")
        @Size(max = 140, message = "reference must be at most 140 characters")
        String reference
) {}