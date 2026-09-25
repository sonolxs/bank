package com.example.bank.repository;

import com.example.bank.domain.Transfer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransferRepository extends JpaRepository<Transfer, String> {

    Optional<Transfer> findByIdempotencyKey(UUID idempotencyKey);

    /**
     * Intenta insertar una transferencia en estado PENDING.
     * Si la idempotency_key ya existe, Postgres no inserta nada y devuelve 0.
     *
     * Este INSERT es el mecanismo de idempotencia: el índice único
     * transfiere la serialización al motor, no la hace el código.
     * No hay SELECT-then-INSERT.
     */
    @Modifying
    @Query(value = """
        INSERT INTO transfers (
            transfer_id, idempotency_key, source_account_id, destination_account_id,
            amount, currency, reference, status, created_at
        ) VALUES (
            :transferId, :idempotencyKey, :sourceAccountId, :destinationAccountId,
            :amount, :currency, :reference, 'PENDING', :createdAt
        )
        ON CONFLICT (idempotency_key) DO NOTHING
        """, nativeQuery = true)
    int insertPendingIfAbsent(@Param("transferId") String transferId,
                              @Param("idempotencyKey") UUID idempotencyKey,
                              @Param("sourceAccountId") String sourceAccountId,
                              @Param("destinationAccountId") String destinationAccountId,
                              @Param("amount") long amount,
                              @Param("currency") String currency,
                              @Param("reference") String reference,
                              @Param("createdAt") Instant createdAt);

    /**
     * Historial de una cuenta: entrantes y salientes, ordenado por created_at DESC.
     * Incluye COMPLETED y FAILED (auditoría completa).
     */
    @Query(value = """
    SELECT * FROM transfers
    WHERE source_account_id = :accountId OR destination_account_id = :accountId
    ORDER BY created_at DESC
    LIMIT :limit OFFSET :offset
    """, nativeQuery = true)
    List<Transfer> findHistoryPage(@Param("accountId") String accountId,
                                   @Param("limit") int limit,
                                   @Param("offset") int offset);

    @Query(value = """
    SELECT COUNT(*) FROM transfers
    WHERE source_account_id = :accountId OR destination_account_id = :accountId
    """, nativeQuery = true)
    long countHistory(@Param("accountId") String accountId);
}