-- =============================================================================
-- V1__init.sql
-- Esquema inicial de la API de transferencias bancarias.
-- Decisiones clave:
--   * balance y amount son BIGINT en unidad mínima (centavos). Nunca float.
--   * CHECK (balance >= 0): invariante de saldo no negativo a nivel motor.
--   * UNIQUE (idempotency_key): mecanismo de idempotencia concurrente.
--   * status con CHECK para impedir estados inválidos.
-- =============================================================================

CREATE TABLE accounts (
                          account_id  TEXT        PRIMARY KEY,
                          currency    VARCHAR(3)  NOT NULL,
                          balance     BIGINT      NOT NULL,
                          status      TEXT        NOT NULL,
                          CONSTRAINT accounts_balance_non_negative CHECK (balance >= 0),
                          CONSTRAINT accounts_currency_iso         CHECK (currency IN ('MXN', 'USD')),
                          CONSTRAINT accounts_status_valid         CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED'))
);

CREATE TABLE transfers (
                           transfer_id             TEXT        PRIMARY KEY,
                           idempotency_key         UUID        NOT NULL,
                           source_account_id       TEXT        NOT NULL,
                           destination_account_id  TEXT        NOT NULL,
                           amount                  BIGINT      NOT NULL,
                           currency                VARCHAR(3)  NOT NULL,
                           reference               VARCHAR(140),
                           status                  TEXT        NOT NULL,
                           failure_code            TEXT,
                           created_at              TIMESTAMPTZ NOT NULL,
                           completed_at            TIMESTAMPTZ,
                           CONSTRAINT transfers_idempotency_unique UNIQUE (idempotency_key),
                           CONSTRAINT transfers_amount_positive    CHECK (amount > 0),
                           CONSTRAINT transfers_currency_iso       CHECK (currency IN ('MXN', 'USD')),
                           CONSTRAINT transfers_status_valid       CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
                           CONSTRAINT transfers_distinct_accounts  CHECK (source_account_id <> destination_account_id),
                           CONSTRAINT transfers_source_fk          FOREIGN KEY (source_account_id) REFERENCES accounts (account_id),
                           CONSTRAINT transfers_destination_fk     FOREIGN KEY (destination_account_id) REFERENCES accounts (account_id)
);

-- Índice para el historial de una cuenta ordenado por created_at DESC.
-- Cubre tanto source como destination porque el historial es entrante + saliente.
CREATE INDEX transfers_source_created_idx      ON transfers (source_account_id, created_at DESC);
CREATE INDEX transfers_destination_created_idx ON transfers (destination_account_id, created_at DESC);

-- =============================================================================
-- Semilla: 8 cuentas del enunciado.
-- Los saldos se expresan en centavos.
-- =============================================================================

INSERT INTO accounts (account_id, currency, balance, status) VALUES
    ('acc_001', 'MXN',   1000000, 'ACTIVE'),  -- 10,000.00
    ('acc_002', 'MXN',    500000, 'ACTIVE'),  --  5,000.00
    ('acc_003', 'MXN',         0, 'ACTIVE'),  --      0.00
    ('acc_004', 'MXN',    250000, 'FROZEN'),  --  2,500.00
    ('acc_005', 'MXN',    100000, 'CLOSED'),  --  1,000.00
    ('acc_006', 'USD',    750000, 'ACTIVE'),  --  7,500.00
    ('acc_007', 'USD',     25000, 'ACTIVE'),  --    250.00
    ('acc_008', 'MXN', 100000000000, 'ACTIVE'); -- 1,000,000,000.00