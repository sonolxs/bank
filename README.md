# Bank — API REST de transferencias bancarias

Servicio HTTP que ejecuta transferencias entre cuentas de forma
**atómica**, **idempotente** y **correcta bajo concurrencia**. El
enfoque no es la funcionalidad (transferir dinero es simple), sino las
tres fallas que rompen sistemas reales:

- **Dinero duplicado por reintentos** → resuelto con
  `UNIQUE (idempotency_key)` e `INSERT ... ON CONFLICT DO NOTHING`.
- **Saldos negativos por condiciones de carrera** → resuelto con
  `SELECT ... FOR UPDATE` en orden determinista de `account_id`.
- **Transferencias a medias cuando el proceso muere** → resuelto con
  modelo síncrono atómico: débito y crédito viven en una sola TX.

Construido con Spec-Driven Development: `docs/spec.md` es el contrato,
se commiteó primero (commit `282e8c3`) y precede a cualquier línea de
código de dominio.

## Stack

Java 17 · Spring Boot 4.1.1 · PostgreSQL 16 · Flyway · Maven ·
Testcontainers 2.x · JUnit 5.

## Cómo levantar el servicio

Requisitos: Docker, JDK 17, Maven (o el wrapper incluido).

Un solo comando:

```bash
docker run --rm -d --name bank-pg \
  -e POSTGRES_DB=bank -e POSTGRES_USER=bank -e POSTGRES_PASSWORD=bank \
  -p 5432:5432 postgres:16 \
  && ./mvnw spring-boot:run
```
(podemos usar las variables de entorno
DB_URL: url de la BD
DB_USER: usuario de la BD
DB_PASSWORD : contraseña de la BD)

Flyway aplica `V1__init.sql` automáticamente al arrancar: crea el
esquema con los `CHECK` y el `UNIQUE`, y siembra las **8 cuentas** del
enunciado. No hay pasos manuales.

Para bajar todo:

```bash
docker stop bank-pg
```

## Cómo correr las pruebas

Un solo comando:

```bash
./mvnw test
```

Testcontainers levanta un PostgreSQL 16 limpio por corrida, aplica
Flyway y ejecuta toda la suite. **Requiere Docker en la máquina.**

Si Docker no está disponible, las pruebas de integración se saltan
automáticamente (anotación `disabledWithoutDocker = true` en
`AbstractIntegrationTest`). La suite unitaria no existe porque todos
los invariantes de este sistema solo se pueden verificar contra un
motor real.



## Variables de entorno

Todas con default. Ninguna es un secreto real.

| Variable | Default | Propósito |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/bank` | URL JDBC |
| `DB_USER` | `bank` | usuario de la base |
| `DB_PASSWORD` | `bank` | password de la base |
| `APP_AUTH_TOKEN` | `dev-token-change-me` | **token por defecto**, cámbialo en producción |
| `SERVER_PORT` | `8080` | puerto HTTP |

`APP_AUTH_TOKEN` es el bearer token que exige la API. En producción
sería rotado y gestionado externamente; aquí es estático y está
documentado a propósito para que el evaluador pueda probar sin
adivinar el valor.

## Contrato de la API

Todos los endpoints requieren `Authorization: Bearer <token>`.
Todos los montos viajan como **string decimal con exactamente 2
decimales** (`"1500.00"`). Todos los IDs son opacos.

### Consultar cuenta

```
GET /accounts/{account_id}
```

```json
{"account_id":"acc_001","currency":"MXN","balance":"10000.00","status":"ACTIVE"}
```

### Crear transferencia

```
POST /transfers
Idempotency-Key: <UUID v4>   (obligatorio)
```

```json
{
  "source_account_id":"acc_001",
  "destination_account_id":"acc_002",
  "amount":"1500.00",
  "currency":"MXN",
  "reference":"Pago de nómina"
}
```

`201 Created` con `status: "COMPLETED"`. Reintento idempotente de una
`COMPLETED` devuelve `200` con el mismo cuerpo. Reintento de una
`FAILED` reproduce el mismo error sin reevaluar la regla.

### Consultar transferencia

```
GET /transfers/{transfer_id}
```

Si es `FAILED`, incluye `failure_code`.

### Historial de movimientos

```
GET /accounts/{account_id}/transfers?limit=20&offset=0
```

Orden `created_at DESC`. `limit` máximo 100. Incluye `COMPLETED` y
`FAILED`. `direction` es `DEBIT` o `CREDIT` según la cuenta consultada.

### Códigos HTTP

| Código | Situación |
|---|---|
| 201 | transferencia creada y completada |
| 200 | reintento idempotente de una `COMPLETED` |
| 400 | forma del payload (monto, escala, moneda, `source=destination`, reference, limit) |
| 401 | token ausente o inválido |
| 404 | cuenta o transferencia inexistente |
| 409 | `Idempotency-Key` con parámetros distintos |
| 422 | regla de negocio con estado (fondos, `FROZEN`/`CLOSED`, moneda) |
| 428 | falta `Idempotency-Key` |
| 500 | falla no controlada, sin filtrar detalles |

### Estructura de error

```json
{"error":{"code":"INSUFFICIENT_FUNDS",
  "message":"Source account has insufficient funds.",
  "transfer_id":"trf_01J9X2K4M7QW"}}
```

`transfer_id` se omite del JSON cuando no hay transferencia asociada.

## Cuentas de la semilla

| account_id | currency | balance | status |
|---|---|---|---|
| acc_001 | MXN | 10000.00 | ACTIVE |
| acc_002 | MXN | 5000.00 | ACTIVE |
| acc_003 | MXN | 0.00 | ACTIVE |
| acc_004 | MXN | 2500.00 | FROZEN |
| acc_005 | MXN | 1000.00 | CLOSED |
| acc_006 | USD | 7500.00 | ACTIVE |
| acc_007 | USD | 250.00 | ACTIVE |
| acc_008 | MXN | 1000000000.00 | ACTIVE |

## Por qué está construido así

Las decisiones no obvias están documentadas en `docs/spec.md`. Resumen:

- **Modelo síncrono atómico.** El `POST /transfers` ejecuta todo en
  una sola transacción: insert de la transferencia, débito, crédito,
  commit. Elimina por construcción la ventana entre débito y crédito.
  `PENDING` existe solo dentro de la TX y nunca es observable por API
  (justificado en spec §2).
- **Idempotencia por índice único.** El `INSERT ... ON CONFLICT
  (idempotency_key) DO NOTHING` **es** el mecanismo. No hay
  `SELECT`-luego-`INSERT`, que sería una carrera. Postgres serializa
  los inserts concurrentes con la misma key en el índice único.
- **Locking pesimista con orden determinista.** `SELECT ... FOR UPDATE`
  sobre ambas cuentas, ordenadas por `account_id` ascendente. Esto
  elimina el deadlock A→B / B→A por construcción: ambos hilos toman
  los locks en el mismo orden.
- **Dinero en `BIGINT` centavos.** Nunca `float`/`double`; nunca
  `BigDecimal` en el dominio (la escala es fija y se valida en el
  borde). La conversión al wire vive en `web/money/MoneyFormat.java`
  con un regex estricto.
- **`CHECK (balance >= 0)` en el esquema.** No solo en el código. Si
  un bug de concurrencia se cuela, Postgres aborta la TX.
- **Zona horaria.** Almacenamiento en UTC (`TIMESTAMPTZ`); la zona de
  negocio es `America/Mexico_City` (`-06:00`).

## Invariantes verificados por la suite

1. **Conservación del dinero.** `Σ balance` invariante ante cualquier
   secuencia de transferencias, exitosas o fallidas.
   (`ConcurrencyIT`)
2. **Saldo no negativo.** Ningún `balance < 0` tras cualquier
   concurrencia. (`ConcurrencyIT` + `CHECK` del esquema)
3. **Atomicidad.** No existe estado con débito aplicado y crédito no
   aplicado. (Modelo síncrono; verificado por `ConcurrencyIT`)
4. **Idempotencia.** N reintentos con la misma key mueven el dinero
   exactamente una vez. (`TransferIdempotencyIT`)
5. **Sin deadlock.** A→B y B→A concurrentes no se bloquean entre sí.
   (`ConcurrencyIT`, 20 hilos con `CountDownLatch`)

## Recortes de alcance declarados

Lo que **no** implementa este proyecto, y por qué:

- **Conversión FX.** Una transferencia es de una sola moneda. El
  enunciado lo declara fuera de alcance; agregar tipos de cambio
  requeriría una fuente de verdad externa que no tenemos.
- **Reversas y cancelaciones.** Un `COMPLETED` es terminal. Añadir
  reversas exige un modelo contable de doble partida que excede el
  objetivo de este ejercicio.
- **Transferencias asíncronas en dos fases.** El modelo síncrono
  atómico resuelve el problema de la ventana entre débito y crédito
  sin outbox ni estado `PENDING` persistente complejo.
- **Gestión de usuarios, OAuth, mTLS.** Un solo token estático en red
  privada es el límite de confianza declarado en spec §9.
- **Rate limiting.** Fuera del modelo de corrección.
- **Métricas Prometheus.** Logs estructurados de eventos de dinero son
  suficientes para la auditabilidad mínima que pide el enunciado.

## Estructura del repositorio

```
├── docs/spec.md              Contrato (≤1500 palabras)
├── AGENTS.md                 Contexto y reglas para agentes de IA
├── NOTES.md                  Bitácora del proceso con IA
├── README.md                 Este archivo
├── pom.xml
├── src/main/java/            Código (domain, repository, service, web, error, util)
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/V1__init.sql
└── src/test/java/            Suite de integración + ConcurrencyIT
```

## Verificación rápida tras clonar

```bash
# 1. Levantar Postgres + servicio
docker run --rm -d --name bank-pg \
  -e POSTGRES_DB=bank -e POSTGRES_USER=bank -e POSTGRES_PASSWORD=bank \
  -p 5432:5432 postgres:16
./mvnw spring-boot:run

# 2. En otra terminal: consultar una cuenta
curl -s http://localhost:8080/accounts/acc_001 \
  -H "Authorization: Bearer dev-token-change-me"

# 3. Crear una transferencia
curl -s -X POST http://localhost:8080/transfers \
  -H "Authorization: Bearer dev-token-change-me" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 8f14e45f-ea0b-4c39-9a1e-2d3b7c1f0a55" \
  -d '{"source_account_id":"acc_001","destination_account_id":"acc_002",
       "amount":"1500.00","currency":"MXN","reference":"Pago"}'

# 4. Repetir el mismo curl: devuelve 200, mismo transfer_id, saldos intactos.

# 5. Correr la suite completa
./mvnw test
```