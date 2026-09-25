package com.example.bank.service;

import com.example.bank.domain.Currency;
import com.example.bank.domain.Transfer;
import com.example.bank.domain.TransferStatus;
import com.example.bank.error.DomainException;
import com.example.bank.error.ErrorCode;
import com.example.bank.error.ValidationException;
import com.example.bank.repository.TransferRepository;
import com.example.bank.util.UlidGenerator;
import com.example.bank.web.money.MoneyFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final TransferPersistence persistence;
    private final TransferRepository transferRepository;

    public TransferService(TransferPersistence persistence,
                           TransferRepository transferRepository) {
        this.persistence = persistence;
        this.transferRepository = transferRepository;
    }

    /**
     * Resultado de una ejecución de transferencia.
     * fresh=true  -> la transferencia se creó en esta request (201).
     * fresh=false -> fue un reintento idempotente de una COMPLETED (200).
     */
    public record ExecutionResult(Transfer transfer, boolean fresh) {}

    public ExecutionResult execute(UUID idempotencyKey,
                                   String sourceAccountId,
                                   String destinationAccountId,
                                   String amountRaw,
                                   String currencyRaw,
                                   String reference) {

        // 1. Validación de forma (400). No toca base.
        Currency currency = parseCurrency(currencyRaw);
        long amountInCents = parseAmount(amountRaw);
        validateShape(sourceAccountId, destinationAccountId, amountInCents, reference);

        TransferPersistence.TransferDraft draft = new TransferPersistence.TransferDraft(
                "trf_" + UlidGenerator.generate(),
                idempotencyKey,
                sourceAccountId,
                destinationAccountId,
                amountInCents,
                currency,
                reference,
                Instant.now()
        );

        // 2. Reserva de idempotency_key en TX propia.
        TransferPersistence.InsertOutcome outcome = persistence.reserveKey(draft);

        if (outcome instanceof TransferPersistence.InsertOutcome.Existing existing) {
            Transfer resolved = resolveExisting(existing.existing(), draft);
            return new ExecutionResult(resolved, false);
        }

        // 3. Es nueva: ejecutar la transferencia.
        try {
            Transfer completed = persistence.executeTransfer(draft);
            log.info("transfer.completed transfer_id={} source={} destination={} amount_cents={} currency={}",
                    completed.getTransferId(), sourceAccountId, destinationAccountId,
                    amountInCents, currency.name());
            return new ExecutionResult(completed, true);
         } catch (DomainException businessFailure) {
        persistence.markFailed(draft.transferId(), businessFailure.getCode());
        log.warn("transfer.failed transfer_id={} code={}",
                draft.transferId(), businessFailure.getCode().name());
        // Re-lanzamos con el transfer_id para que el cliente lo reciba.
        throw new DomainException(
                businessFailure.getCode(),
                businessFailure.getStatus(),
                businessFailure.getMessage(),
                draft.transferId()
        );
    }
    }

    private Transfer resolveExisting(Transfer existing, TransferPersistence.TransferDraft draft) {
        boolean sameParams = existing.matchesRequest(
                draft.sourceAccountId(),
                draft.destinationAccountId(),
                draft.amountInCents(),
                draft.currency(),
                draft.reference()
        );

        if (!sameParams) {
            throw new DomainException(
                    ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                    HttpStatus.CONFLICT,
                    "Idempotency-Key reused with different parameters.",
                    existing.getTransferId()
            );
        }

        if (existing.getStatus() == TransferStatus.FAILED) {
            ErrorCode originalCode = ErrorCode.valueOf(existing.getFailureCode());
            throw new DomainException(
                    originalCode,
                    statusForCode(originalCode),
                    messageForCode(originalCode),
                    existing.getTransferId()
            );
        }

        if (existing.getStatus() == TransferStatus.PENDING) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Transfer in PENDING state observed outside transaction.");
        }


        return existing;
    }

    @Transactional(readOnly = true)
    public Transfer getById(String transferId) {
        return transferRepository.findById(transferId)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.ACCOUNT_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "Transfer not found: " + transferId));
    }

    @Transactional(readOnly = true)
    public HistoryPage history(String accountId, int limit, int offset) {
        if (limit < 1 || limit > 100) {
            throw new ValidationException(ErrorCode.LIMIT_OUT_OF_RANGE,
                    "limit must be between 1 and 100.");
        }
        if (offset < 0) {
            throw new ValidationException(ErrorCode.LIMIT_OUT_OF_RANGE,
                    "offset must be >= 0.");
        }
        List<Transfer> items = transferRepository.findHistoryPage(accountId, limit, offset);
        long total = transferRepository.countHistory(accountId);
        return new HistoryPage(items, total);
    }

    public record HistoryPage(List<Transfer> items, long total) {}

    // ------------------------------------------------------------------
    // Validaciones de forma (400). No tocan base.
    // ------------------------------------------------------------------

    private Currency parseCurrency(String raw) {
        try {
            return Currency.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new ValidationException(ErrorCode.INVALID_CURRENCY,
                    "currency must be one of MXN, USD.");
        }
    }

    private long parseAmount(String raw) {
        if (!MoneyFormat.isValid(raw)) {
            throw new ValidationException(ErrorCode.AMOUNT_SCALE_INVALID,
                    "amount must be a decimal string with exactly 2 decimals.");
        }
        try {
            return MoneyFormat.toCents(raw);
        } catch (ArithmeticException e) {
            throw new ValidationException(ErrorCode.AMOUNT_SCALE_INVALID,
                    "amount exceeds supported range.");
        }
    }

    private void validateShape(String source, String destination,
                               long amountInCents, String reference) {
        if (source.equals(destination)) {
            throw new ValidationException(ErrorCode.SAME_ACCOUNT,
                    "source_account_id and destination_account_id must differ.");
        }
        if (amountInCents <= 0) {
            throw new ValidationException(ErrorCode.AMOUNT_NOT_POSITIVE,
                    "amount must be positive.");
        }
        if (reference != null && reference.length() > 140) {
            throw new ValidationException(ErrorCode.REFERENCE_TOO_LONG,
                    "reference must be at most 140 characters.");
        }
    }

    private HttpStatus statusForCode(ErrorCode code) {
        return switch (code) {
            case INSUFFICIENT_FUNDS, ACCOUNT_NOT_ACTIVE, CURRENCY_MISMATCH ->
                    HttpStatus.UNPROCESSABLE_ENTITY;
            case ACCOUNT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case IDEMPOTENCY_KEY_CONFLICT -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    private String messageForCode(ErrorCode code) {
        return switch (code) {
            case INSUFFICIENT_FUNDS -> "Source account has insufficient funds.";
            case ACCOUNT_NOT_ACTIVE -> "One of the accounts is not active.";
            case CURRENCY_MISMATCH -> "Currency mismatch with one of the accounts.";
            case ACCOUNT_NOT_FOUND -> "One of the accounts does not exist.";
            default -> "Transfer rejected.";
        };
    }
}