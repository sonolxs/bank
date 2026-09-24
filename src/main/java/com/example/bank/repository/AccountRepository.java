package com.example.bank.repository;

import com.example.bank.domain.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AccountRepository extends JpaRepository<Account, String> {

    /**
     * Bloquea las cuentas indicadas en orden ascendente de account_id.
     *
     * El ORDER BY dentro del IN es la pieza clave que evita el deadlock
     * A→B / B→A: ambos hilos adquieren los locks en el mismo orden global.
     * Postgres aplica los FOR UPDATE en el orden del ORDER BY de la query.
     *
     * NUNCA usar findAllById(...) para locking: el orden no está garantizado.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.accountId IN :ids ORDER BY a.accountId ASC")
    List<Account> findAllForUpdateOrderedById(@Param("ids") List<String> ids);

    @Query("SELECT a FROM Account a WHERE a.accountId = :id")
    Optional<Account> findByIdReadOnly(@Param("id") String id);
}