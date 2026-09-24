# Spec — API REST de Transferencias Bancarias

## 1. Propósito y alcance

Servicio HTTP que permite consultar cuentas, ejecutar transferencias entre cuentas de la misma moneda y consultar transferencias e historial de movimientos. El sistema garantiza **atomicidad**, **idempotencia** y **conservación del dinero** bajo concurrencia.

**Fuera de alcance (declarado):** conversión FX, reversas, cancelaciones, usuarios/registro/login, rate limiting, UI, métricas Prometheus, patrón outbox, transferencias asíncronas en dos fases.

Este documento es el contrato. El código lo obedece.

---

## 2. Modelo de ejecución

**Síncrono atómico.** `POST /transfers` ejecuta en una única transacción de PostgreSQL: insert de la transferencia, débito al origen, crédito al destino, y actualización del estado a `COMPLETED` (o `FAILED` con causa). El cliente recibe `201` con la transferencia ya resuelta o `422` con el `FAILED` persistido.

**Justificación:** elimina por construcción la clase de bug "proceso muere entre débito y crédito". No existe ventana entre ambos: comparten TX. `PENDING` existe solo como estado interno transitorio durante la TX, nunca observable por la API.

---

## 3. Modelo de datos

### 3.1 `accounts`
| columna | tipo | notas |
|---|---|---|
| `account_id` | `TEXT` PK | opaco, `acc_XXX` |
| `currency` | `CHAR(3)` | ISO-4217: `MXN`, `USD` |
| `balance` | `BIGINT` | centavos, `CHECK (balance >= 0)` |
| `status` | `TEXT` | `ACTIVE` \| `FROZEN` \| `CLOSED` |

### 3.2 `transfers`
| columna | tipo | notas |
|---|---|---|
| `transfer_id` | `TEXT` PK | ULID con prefijo `trf_` |
| `idempotency_key` | `UUID` **UNIQUE NOT NULL** | |
| `source_account_id` | `TEXT` FK | |
| `destination_account_id` | `TEXT` FK | |
| `amount` | `BIGINT` | centavos, `CHECK (amount > 0)` |
| `currency` | `CHAR(3)` | |
| `reference` | `VARCHAR(140)` NULL | |
| `status` | `TEXT` | `PENDING` \| `COMPLETED` \| `FAILED` |
| `failure_code` | `TEXT` NULL | enum de error si `FAILED` |
| `created_at` | `TIMESTAMPTZ` | UTC |
| `completed_at` | `TIMESTAMPTZ` NULL | UTC |

Constraints clave:
- `UNIQUE (idempotency_key)` → mecanismo de idempotencia.
- `CHECK (balance >= 0)` en `accounts` → invariante a nivel motor, no solo en código.
- `CHECK (amount > 0)` en `transfers`.
- `CHECK (source_account_id <> destination_account_id)`.

**Representación monetaria:** `BIGINT` en unidad mínima (centavos). Nunca `float`/`double`. Nunca `BigDecimal` en dominio: la escala es fija (2 decimales) y se valida en el borde. En el wire viaja como string decimal con 2 decimales exactos (`"1500.00"`).

---

## 4. Estados de la transferencia

```
PENDING ──► COMPLETED
   └─────► FAILED (con failure_code)
```

- `PENDING`: estado transitorio dentro de la TX. Nunca visible por API.
- `COMPLETED`: débito y crédito aplicados, TX commiteada.
- `FAILED`: transferencia rechazada por regla de negocio, persistida con causa. Los saldos **no** se modificaron (rollback).

Transiciones inválidas son imposibles: `COMPLETED` y `FAILED` son terminales. No hay API para modificar el estado de una transferencia.

---

## 5. Idempotencia

El cliente envía `Idempotency-Key: <UUID v4>`. Formato distinto → `400 MALFORMED_IDEMPOTENCY_KEY`. Ausente → `428`.

**Mecanismo:** el `INSERT` de la transferencia con `UNIQUE (idempotency_key)` **es** el mecanismo de idempotencia y de serialización por clave. No se usa `SELECT` seguido de `INSERT`.

Flujo de `POST /transfers`:

1. Autenticación (`401`).
2. Validación de forma del `Idempotency-Key` (`428`/`400`).
3. Validación sin estado del payload (`400`): monto positivo, escala 2, moneda ISO, `source ≠ destination`, `reference ≤ 140`.
4. `INSERT ... ON CONFLICT (idempotency_key) DO NOTHING RETURNING *` con `status='PENDING'` y parámetros originales.
    - **Insertó fila** → es nueva, continuar al paso 5.
    - **No insertó** → la clave existe. `SELECT` la fila original:
        - Mismos `source_account_id`, `destination_account_id`, `amount`, `currency`, `reference`:
            - original `COMPLETED` → `200` con el resultado original.
            - original `FAILED` → reproducir el mismo código y mensaje de error, **sin reevaluar reglas**. Si era `422 INSUFFICIENT_FUNDS`, responde `422 INSUFFICIENT_FUNDS` aunque ahora haya fondos.
        - Parámetros distintos → `409 IDEMPOTENCY_KEY_CONFLICT`.
5. `SELECT ... FOR UPDATE` de ambas cuentas, ordenadas por `account_id` ascendente.
6. Validaciones con estado (`404`/`422`): existencia, `status=ACTIVE`, moneda coincide, saldo suficiente.
7. `UPDATE` débito, `UPDATE` crédito, `UPDATE transfer SET status='COMPLETED', completed_at=now()`.
8. `COMMIT`.

Si en cualquier paso 6–7 se viola una regla, la TX hace `ROLLBACK` del insert `PENDING` y se reinserta la transferencia con `status='FAILED'` y `failure_code`, en una segunda TX corta. El reintento idempotente de un `FAILED` reproduce el error original.

**Sobre `IDEMPOTENT_REQUEST_IN_PROGRESS`:** no se implementa como código explícito. Dos requests concurrentes con la misma clave quedan serializados por el índice único de Postgres: el segundo espera a que el primero commitee o aborte. No es un `409` espontáneo, es un bloqueo transitivo. La spec lo declara para justificar la ausencia del código.

---

## 6. Concurrencia y orden de bloqueo

**Estrategia:** bloqueo pesimista con `SELECT ... FOR UPDATE` sobre ambas cuentas, **adquiridos en orden ascendente de `account_id`**.

**Por qué:** la adquisición en orden determinista global elimina el deadlock A→B / B→A por construcción. Ambos hilos toman los mismos locks en el mismo orden, así que no pueden cruzarse.

**A→B y B→A concurrentes:** ambos hilos ordenan `[min(account_id), max(account_id)]` antes de bloquear. No hay deadlock. El segundo espera al primero y aplica su propia transferencia sobre el saldo resultante, respetando `CHECK (balance >= 0)`.

**Idempotencia concurrente:** el índice único serializa. No hay `SELECT`-luego-`INSERT`.

---

## 7. Contrato HTTP

`Content-Type: application/json`. Todos los endpoints requieren `Authorization: Bearer <token>`. Montos en string decimal con 2 decimales. IDs opacos.

### 7.1 `GET /accounts/{account_id}`
`200`:
```json
{"account_id":"acc_001","currency":"MXN","balance":"10000.00","status":"ACTIVE"}
```
`404 ACCOUNT_NOT_FOUND`.

### 7.2 `POST /transfers`
Headers: `Idempotency-Key: <uuid>`, `Authorization: Bearer <token>`.
Body:
```json
{"source_account_id":"acc_001","destination_account_id":"acc_002",
 "amount":"1500.00","currency":"MXN","reference":"Pago de nómina"}
```
`201`:
```json
{"transfer_id":"trf_01J9X2K4M7QW","status":"COMPLETED",
 "source_account_id":"acc_001","destination_account_id":"acc_002",
 "amount":"1500.00","currency":"MXN","reference":"Pago de nómina",
 "created_at":"2026-08-03T10:15:30-06:00",
 "idempotency_key":"8f14e45f-ea0b-4c39-9a1e-2d3b7c1f0a55"}
```
Reintento idempotente de `COMPLETED` → `200` mismo body.
Reintento idempotente de `FAILED` → mismo `422` con misma causa.

### 7.3 `GET /transfers/{transfer_id}`
`200` con la transferencia; si `FAILED`, incluye `failure_code`. `404` si no existe.

### 7.4 `GET /accounts/{account_id}/transfers?limit=20&offset=0`
`limit` default 20, máximo 100. Orden `created_at DESC`. Incluye `COMPLETED` y `FAILED`.
```json
{"account_id":"acc_001",
 "items":[{"transfer_id":"trf_...","direction":"DEBIT","amount":"1500.00",
   "currency":"MXN","counterparty_account_id":"acc_002",
   "status":"COMPLETED","created_at":"2026-08-03T10:15:30-06:00"}],
 "limit":20,"offset":0,"total":1}
```

### 7.5 Códigos HTTP
| código | situación |
|---|---|
| 201 | transferencia creada y completada |
| 200 | reintento idempotente de `COMPLETED` |
| 400 | forma del payload, monto, escala, moneda, `source=destination`, `reference`, `limit` |
| 401 | token ausente o inválido |
| 404 | cuenta o transferencia inexistente |
| 409 | `Idempotency-Key` con parámetros distintos |
| 422 | regla de negocio con estado: fondos, `FROZEN`/`CLOSED`, moneda |
| 428 | falta `Idempotency-Key` |
| 500 | falla no controlada, sin filtrar detalles |

**Separación 400/422:** 400 = comprobable sin consultar estado. 422 = requiere estado de cuenta o saldo.

### 7.6 Estructura de error
```json
{"error":{"code":"INSUFFICIENT_FUNDS",
  "message":"El saldo de la cuenta origen es insuficiente.",
  "transfer_id":"trf_01J9X2K4M7QW"}}
```
`transfer_id` presente cuando hay transferencia asociada.

### 7.7 Enum de `code`
`VALIDATION_ERROR`, `MALFORMED_IDEMPOTENCY_KEY`, `INVALID_CURRENCY`, `SAME_ACCOUNT`, `AMOUNT_NOT_POSITIVE`, `AMOUNT_SCALE_INVALID`, `REFERENCE_TOO_LONG`, `LIMIT_OUT_OF_RANGE`, `UNAUTHORIZED`, `ACCOUNT_NOT_FOUND`, `IDEMPOTENCY_KEY_CONFLICT`, `INSUFFICIENT_FUNDS`, `ACCOUNT_NOT_ACTIVE`, `CURRENCY_MISMATCH`, `INTERNAL_ERROR`.

---

## 8. Validaciones de dominio

- Monto > 0, exactamente 2 decimales (`"10.005"` → `400 AMOUNT_SCALE_INVALID`).
- `source ≠ destination` (`400 SAME_ACCOUNT`).
- Ambas cuentas existen (`404`) y están `ACTIVE` (`422 ACCOUNT_NOT_ACTIVE`).
- Moneda de la transferencia = moneda de ambas cuentas (`422 CURRENCY_MISMATCH`).
- `reference` opcional, ≤ 140 caracteres, almacenado como texto plano (`VARCHAR(140)`).
- `limit` ∈ [1, 100], `offset` ≥ 0.

**Orden determinista** de validaciones: auth → idempotency-key forma → payload forma → idempotencia contra `transfers` → cuentas (existencia, status, moneda) → saldo → ejecución.

---

## 9. Requisitos no funcionales

**Precisión monetaria:** `BIGINT` centavos. Wire en string decimal con 2 decimales. `float`/`double` prohibidos por diseño y por test.

**Errores:** JSON estructurado, sin stack traces. `500` genérico.

**Validación de entrada:** todo input externo se valida antes de tocar el dominio. Defensa contra montos gigantes (`Long.MAX_VALUE`), negativos, notación científica, strings largos, intentos de inyección en `reference` (tratado como dato, nunca concatenado a SQL).

**Autenticación:** `Authorization: Bearer <token>` con token estático desde `APP_AUTH_TOKEN`. Comparación en tiempo constante. **Límite de confianza:** este diseño asume un solo cliente de confianza en red privada. En producción faltaría: rotación de tokens, scopes, mTLS, rate limiting, y autorización por cuenta.

**Trazabilidad:** cada transferencia registra `idempotency_key`, parámetros, `created_at`, `completed_at` y `failure_code` si aplica.

**Logs:** estructurados (JSON), sin payloads completos con montos+cuentas, sin token. Eventos: creación, resultado, rechazo con `code`.

**Zona horaria:** almacenamiento en UTC (`TIMESTAMPTZ`). Serialización ISO-8601 con offset `-06:00` (America/Mexico_City).

---

## 10. Invariantes verificables (base de las pruebas)

1. **Conservación:** `Σ balance` es invariante ante cualquier secuencia de transferencias, exitosas o fallidas.
2. **Saldo no negativo:** ningún `balance < 0` tras cualquier concurrencia.
3. **Atomicidad:** no existe estado con débito aplicado y crédito no aplicado (o viceversa).
4. **Idempotencia:** N reintentos con la misma key mueven el dinero exactamente una vez.
5. **Orden de locks:** A→B y B→A concurrentes no producen deadlock.

Estas cinco aserciones son obligatorias en la suite de pruebas.