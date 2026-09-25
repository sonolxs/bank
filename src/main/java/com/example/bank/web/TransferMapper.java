package com.example.bank.web;

import com.example.bank.domain.Account;
import com.example.bank.domain.Transfer;
import com.example.bank.web.dto.AccountResponse;
import com.example.bank.web.dto.HistoryItemResponse;
import com.example.bank.web.dto.TransferResponse;
import com.example.bank.web.money.MoneyFormat;
import org.springframework.stereotype.Component;

@Component
public class TransferMapper {

    public AccountResponse toAccountResponse(Account account) {
        return new AccountResponse(
                account.getAccountId(),
                account.getCurrency().name(),
                MoneyFormat.fromCents(account.getBalance()),
                account.getStatus().name()
        );
    }

    public TransferResponse toTransferResponse(Transfer t) {
        return new TransferResponse(
                t.getTransferId(),
                t.getStatus().name(),
                t.getSourceAccountId(),
                t.getDestinationAccountId(),
                MoneyFormat.fromCents(t.getAmount()),
                t.getCurrency().name(),
                t.getReference(),
                t.getCreatedAt(),
                t.getIdempotencyKey(),
                t.getFailureCode()
        );
    }

    /**
     * Dirección vista desde la cuenta del historial:
     *  - DEBIT si la cuenta es la origen.
     *  - CREDIT si es la destino.
     * El counterparty es la "otra" cuenta.
     */
    public HistoryItemResponse toHistoryItem(String accountId, Transfer t) {
        boolean isSource = t.getSourceAccountId().equals(accountId);
        String direction = isSource ? "DEBIT" : "CREDIT";
        String counterparty = isSource ? t.getDestinationAccountId() : t.getSourceAccountId();

        return new HistoryItemResponse(
                t.getTransferId(),
                direction,
                MoneyFormat.fromCents(t.getAmount()),
                t.getCurrency().name(),
                counterparty,
                t.getStatus().name(),
                t.getCreatedAt()
        );
    }
}