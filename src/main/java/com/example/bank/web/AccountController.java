package com.example.bank.web;

import com.example.bank.domain.Account;
import com.example.bank.service.AccountService;
import com.example.bank.service.TransferService;
import com.example.bank.web.dto.AccountResponse;
import com.example.bank.web.dto.HistoryItemResponse;
import com.example.bank.web.dto.HistoryResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;
    private final TransferService transferService;
    private final TransferMapper mapper;

    public AccountController(AccountService accountService,
                             TransferService transferService,
                             TransferMapper mapper) {
        this.accountService = accountService;
        this.transferService = transferService;
        this.mapper = mapper;
    }

    @GetMapping("/{accountId}")
    public ResponseEntity<AccountResponse> getAccount(@PathVariable String accountId) {
        Account account = accountService.getById(accountId);
        return ResponseEntity.ok(mapper.toAccountResponse(account));
    }

    @GetMapping("/{accountId}/transfers")
    public ResponseEntity<HistoryResponse> getHistory(
            @PathVariable String accountId,
            @RequestParam(name = "limit", defaultValue = "20") int limit,
            @RequestParam(name = "offset", defaultValue = "0") int offset) {

        // La cuenta debe existir; de lo contrario 404 antes de paginar.
        accountService.getById(accountId);

        TransferService.HistoryPage page = transferService.history(accountId, limit, offset);

        List<HistoryItemResponse> items = page.items().stream()
                .map(t -> mapper.toHistoryItem(accountId, t))
                .toList();

        return ResponseEntity.ok(new HistoryResponse(
                accountId, items, limit, offset, page.total()
        ));
    }
}