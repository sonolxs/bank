package com.example.bank.service;

import com.example.bank.domain.Account;
import com.example.bank.domain.Transfer;
import com.example.bank.error.DomainException;
import com.example.bank.error.ErrorCode;
import com.example.bank.repository.AccountRepository;
import com.example.bank.repository.TransferRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Capa de persistencia transaccional. Existe como bean separado para
 * que TransferService pueda invocarla a través del proxy de Spring y
 * las anotaciones @Transactional sí se apliquen (evitar el self-invocation
 * pitfall).
 *
 * Toda la manipulación de entidades JPA y de TX vive aquí.
 * TransferService solo orquesta.
 */
@Component
public class TransferPersistence {

    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;

    public TransferPersistence(AccountRepository accountRepository,
                               TransferRepository transferRepository) {
        this.accountRepository = accountRepository;
        this.transferRepository = transferRepository;
    }

    /**
     * Fase 1: reserva la idempotency_key en su propia TX.
     *
     * Devuelve:
     *   - InsertOutcome.NEW      si el INSERT tuvo éxito.
     *   - InsertOutcome.EXISTING si la key ya existía (trae la fila).
     *
     * El INSERT ... ON CONFLICT DO NOTHING es el mecanismo de idempotencia
     * concurrente: Postgres serializa dos inserts con la misma key en el
     * índice único, sin necesidad de SELECT-then-INSERT.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public InsertOutcome reserveKey(TransferDraft draft) {
        int inserted = transferRepository.insertPendingIfAbsent(
                draft.transferId(),
                draft.idempotencyKey(),
                draft.sourceAccountId(),
                draft.destinationAccountId(),
                draft.amountInCents(),
                draft.currency().name(),
                draft.reference(),
                draft.createdAt()
        );

        if (inserted == 1) {
            return new InsertOutcome.New(draft);
        }

        Transfer existing = transferRepository.findByIdempotencyKey(draft.idempotencyKey())
                .orElseThrow(() -> new DomainException(
                        ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR,
                        "Idempotency key exists but row not found."
                ));
        return new InsertOutcome.Existing(existing);
    }

    /**
     * Fase 2: bloquea cuentas en orden determinista, mueve el dinero
     * y marca la transferencia como COMPLETED. Todo en una sola TX.
     */
    @Transactional
    public Transfer executeTransfer(TransferDraft draft) {
        // Orden determinista por account_id: previene deadlock A→B / B→A.
        List<String> orderedIds = draft.sourceAccountId().compareTo(draft.destinationAccountId()) < 0
                ? List.of(draft.sourceAccountId(), draft.destinationAccountId())
                : List.of(draft.destinationAccountId(), draft.sourceAccountId());

        List<Account> locked = accountRepository.findAllForUpdateOrderedById(orderedIds);

        Account source = pick(locked, draft.sourceAccountId(), "Source");
        Account destination = pick(locked, draft.destinationAccountId(), "Destination");

        if (!source.getStatus().canOperate()) {
            throw new DomainException(ErrorCode.ACCOUNT_NOT_ACTIVE,
                    HttpStatus.UNPROCESSABLE_ENTITY, "Source account is not active.");
        }
        if (!destination.getStatus().canOperate()) {
            throw new DomainException(ErrorCode.ACCOUNT_NOT_ACTIVE,
                    HttpStatus.UNPROCESSABLE_ENTITY, "Destination account is not active.");
        }
        if (source.getCurrency() != draft.currency()) {
            throw new DomainException(ErrorCode.CURRENCY_MISMATCH,
                    HttpStatus.UNPROCESSABLE_ENTITY, "Source account currency mismatch.");
        }
        if (destination.getCurrency() != draft.currency()) {
            throw new DomainException(ErrorCode.CURRENCY_MISMATCH,
                    HttpStatus.UNPROCESSABLE_ENTITY, "Destination account currency mismatch.");
        }
        if (source.getBalance() < draft.amountInCents()) {
            throw new DomainException(ErrorCode.INSUFFICIENT_FUNDS,
                    HttpStatus.UNPROCESSABLE_ENTITY, "Source account has insufficient funds.");
        }

        source.debit(draft.amountInCents());
        destination.credit(draft.amountInCents());

        Transfer transfer = transferRepository.findById(draft.transferId())
                .orElseThrow(() -> new DomainException(
                        ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR,
                        "Transfer row vanished mid-transaction."));
        transfer.complete(Instant.now());
        return transfer;
    }

    /**
     * Marca FAILED en TX nueva, para que el registro PENDING quede
     * persistido y los reintentos idempotentes reproduzcan el error.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String transferId, ErrorCode code) {
        Optional<Transfer> found = transferRepository.findById(transferId);
        if (found.isEmpty()) {
            return;
        }
        Transfer t = found.get();
        if (t.getStatus() != com.example.bank.domain.TransferStatus.PENDING) {
            return;
        }
        t.fail(code.name(), Instant.now());
    }

    private Account pick(List<Account> locked, String accountId, String role) {
        return locked.stream()
                .filter(a -> a.getAccountId().equals(accountId))
                .findFirst()
                .orElseThrow(() -> new DomainException(
                        ErrorCode.ACCOUNT_NOT_FOUND, HttpStatus.NOT_FOUND,
                        role + " account not found: " + accountId));
    }

    /**
     * Borrador de transferencia. Inmutable. Se construye en TransferService
     * después de validar forma y antes de tocar persistencia.
     */
    public record TransferDraft(
            String transferId,
            UUID idempotencyKey,
            String sourceAccountId,
            String destinationAccountId,
            long amountInCents,
            com.example.bank.domain.Currency currency,
            String reference,
            Instant createdAt
    ) {}

    public sealed interface InsertOutcome {
        record New(TransferDraft draft) implements InsertOutcome {}
        record Existing(Transfer existing) implements InsertOutcome {}
    }
}