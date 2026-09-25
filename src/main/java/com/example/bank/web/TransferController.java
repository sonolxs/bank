package com.example.bank.web;

import com.example.bank.domain.Transfer;
import com.example.bank.error.ErrorCode;
import com.example.bank.error.ValidationException;
import com.example.bank.service.TransferService;
import com.example.bank.web.dto.TransferRequest;
import com.example.bank.web.dto.TransferResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;
    private final TransferMapper mapper;

    public TransferController(TransferService transferService,
                              TransferMapper mapper) {
        this.transferService = transferService;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> create(
            @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {

        UUID key = parseIdempotencyKey(idempotencyKey);

        TransferService.ExecutionResult result = transferService.execute(
                key,
                request.sourceAccountId(),
                request.destinationAccountId(),
                request.amount(),
                request.currency(),
                request.reference()
        );

        TransferResponse body = mapper.toTransferResponse(result.transfer());
        HttpStatus status = result.fresh() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(body);
    }

    @GetMapping("/{transferId}")
    public ResponseEntity<TransferResponse> getOne(@PathVariable String transferId) {
        Transfer transfer = transferService.getById(transferId);
        return ResponseEntity.ok(mapper.toTransferResponse(transfer));
    }

    private UUID parseIdempotencyKey(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new ValidationException(
                    ErrorCode.MALFORMED_IDEMPOTENCY_KEY,
                    "Idempotency-Key must be a valid UUID."
            );
        }
    }
}