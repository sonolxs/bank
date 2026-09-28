# AGENTS.md — Contexto para agentes de IA

Este documento describe el proyecto, sus invariantes intocables y las
reglas operativas para trabajar en él. **Es un contrato, no una guía.**
El código obedece a `docs/spec.md`; este archivo describe cómo
modificarlo sin romperlo.

---

## Qué es este proyecto

API REST de transferencias bancarias con garantías de atomicidad,
idempotencia y conservación de dinero bajo concurrencia. Java 17,
Spring Boot 4.1.1, PostgreSQL 16, Flyway, Maven.

Se construyó con Spec-Driven Development: `docs/spec.md` es el contrato
y precede a todo el código. Cualquier cambio en el comportamiento de la
API **empieza en la spec, no en el código.**

---

## Invariantes intocables

Un cambio que viole cualquiera de estos debe ser rechazado. Si crees
que uno debe cambiar, primero se discute en `docs/spec.md`.

1. **Dinero es entero en centavos.** `long` en Java, `BIGINT` en
   Postgres. Prohibido `float`, `double`, `BigDecimal` en el dominio.
   La única conversión a string decimal con 2 dígitos vive en
   `web/money/MoneyFormat.java`.

2. **`CHECK (balance >= 0)` vive en el esquema**, no solo en el código.
   Si una migración la quita, el sistema pierde su última defensa.

3. **`UNIQUE (idempotency_key)` es el mecanismo de idempotencia.** No
   hay `SELECT` seguido de `INSERT`. El `INSERT ... ON CONFLICT DO
   NOTHING` de `TransferRepository.insertPendingIfAbsent` es la única
   vía de creación de transferencias.

4. **Las cuentas se bloquean en orden ascendente de `account_id`.**
   La query `AccountRepository.findAllForUpdateOrderedById` tiene el
   `ORDER BY` **dentro** del `IN` a propósito. Nunca usar
   `findAllById` para locking; nunca cambiar ese orden.

5. **Un `COMPLETED` no vuelve a `PENDING`.** Un `FAILED` no vuelve a
   `PENDING`. Las transiciones viven en `Transfer.complete()` y
   `Transfer.fail()` y fallan si el estado de partida no es `PENDING`.

6. **La conservación del dinero se verifica con tests, no con la
   razón.** `ConcurrencyIT` es la prueba que atrapa regresiones en el
   locking. Si tocas `TransferPersistence` o
   `AccountRepository.findAllForUpdateOrderedById`, córrela antes de
   commitear.

---

## Mapa del código

```
src/main/java/com/example/bank/
├── domain/           Entidades JPA + invariantes (debit/credit,
│                     transiciones de Transfer)
├── repository/       Spring Data JPA. findAllForUpdateOrderedById
│                     e insertPendingIfAbsent son críticas.
├── service/          TransferService orquesta; TransferPersistence
│                     tiene las TX y el locking. Separados por el
│                     proxy de Spring.
├── web/              Controladores, DTOs, mapper, GlobalExceptionHandler,
│                     filtro Bearer. MoneyFormat aquí.
├── error/            ErrorCode (enum) y jerarquía de excepciones.
└── util/             UlidGenerator.

src/main/resources/
├── application.yml           Config base.
└── db/migration/V1__init.sql Esquema. Inmutable una vez aplicada.

src/test/java/
├── AbstractIntegrationTest   Base con Testcontainers + @ServiceConnection.
├── web/*IT                   Tests de integración por endpoint.
└── concurrency/ConcurrencyIT Prueba de invariantes bajo concurrencia.
```

---

## Reglas operativas para un agente

1. **No modifiques `V1__init.sql` si ya está aplicada en algún
   entorno.** Flyway valida el checksum y romperá. Cambios de esquema
   van en `V2__...sql`. Excepción documentada: en desarrollo temprano
   se corrigió `CHAR(3)` a `VARCHAR(3)` sobre `V1` antes de que la
   migración se estabilizara.

2. **No agregues `spring.jpa.hibernate.ddl-auto` con valor distinto a
   `validate`.** Si cambias a `create` o `update`, el esquema deja de
   ser el de Flyway y las constraints de Postgres desaparecen del test
   real. `validate` es deliberado.

3. **No metas `@Transactional` en controladores.** Vive en
   `TransferPersistence` y en los métodos `readOnly` de
   `TransferService`. Si agregas un método transaccional en
   `TransferService`, hazlo a través de `TransferPersistence` o de un
   bean separado, nunca por autollamada.

4. **No loguees payloads completos ni el token.** La spec §9 lo
   prohíbe. Logs estructurados con `transfer_id`, `code` y montos en
   centavos. Nada más.

5. **Toda entrada externa se valida antes de tocar el dominio.** El
   orden es: auth → forma del `Idempotency-Key` → forma del payload
   (400) → idempotencia contra `transfers` → cuentas (404/422) → saldo
   → ejecución. La idempotencia se resuelve **antes** de reevaluar
   reglas.

6. **El código enum `ErrorCode` es estable.** Agregar un valor es un
   cambio de contrato de API: documentarlo en `docs/spec.md` y en
   `README.md`.

---

## Comandos

```bash
# Arrancar el servicio (requiere Postgres)
docker run --rm -d --name bank-pg -e POSTGRES_DB=bank \
  -e POSTGRES_USER=bank -e POSTGRES_PASSWORD=bank \
  -p 5432:5432 postgres:16
./mvnw spring-boot:run

# Correr toda la suite (levanta Postgres vía Testcontainers)
./mvnw test

# Solo la prueba de concurrencia
./mvnw test -Dtest=ConcurrencyIT

# Verificar el orden de commits (spec antes que dominio)
git log --diff-filter=A --format="%h %ad %s" --date=short -- docs/spec.md
```

---

## Al modificar la API

1. Actualizar `docs/spec.md` **primero**.
2. Actualizar `AGENTS.md` si cambian los invariantes o el mapa.
3. Actualizar `README.md` si cambian los comandos o las variables de
   entorno.
4. Añadir un test de integración en `src/test/java/.../web/` que
   verifique el nuevo comportamiento, **antes** o junto con el cambio.
5. Si el cambio toca locking, TX o persistencia de dinero, correr
   `ConcurrencyIT` antes de commitear.

---

## Lo que este proyecto NO tiene

No es un sistema bancario completo. No hay: conversión FX, reversas,
cancelaciones, outbox, dos fases, gestión de usuarios, OAuth, mTLS,
rate limiting. Cada omisión está declarada en `README.md` con su porqué.
Si una tarea requiere alguna de estas cosas, **no la implementes sin
antes actualizar la spec y declararla como fuera de alcance previo**.