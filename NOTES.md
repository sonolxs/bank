# NOTES — Bitácora del proceso con IA

Este documento registra cómo se usó asistencia de IA durante el desarrollo,
qué se aceptó, qué se rechazó, y qué correcciones concretas se hicieron.
Cada corrección cita el commit que la respalda.

## Método

Spec-Driven Development estricto: `docs/spec.md` fue el primer commit
(`282e8c3`) antes de cualquier línea de código de dominio. La IA se usó
como par de diseño y como generador de borradores, nunca como autoridad.
Toda decisión técnica pasó por tres filtros: coherencia con la spec,
validación del motor (Postgres, Hibernate, Flyway) y tests automatizados.

## Correcciones concretas

### 1. `transfer_id: null` en errores de dominio — commit `e235b96`

**Detectada por:** prueba manual en PowerShell durante el paso 6.5.7.

El contrato de la spec dice: *"transfer_id aparece cuando existe una
transferencia asociada"*. Al reintentar idempotentemente un `FAILED`,
el sistema devolvía `422 INSUFFICIENT_FUNDS` correctamente, pero el
cuerpo traía `"transfer_id":null` aunque la transferencia existía.

**Causa raíz:** `GlobalExceptionHandler` pasaba `null` literal al
construir `ErrorResponse.ErrorBody`; `DomainException` no tenía campo
`transferId` para propagarlo desde el servicio.

**Corrección:** `DomainException` ganó un campo opcional `transferId`
con un constructor de cuatro argumentos. `TransferService` lo propaga
en tres rutas: `FAILED` reproducido, `409 IDEMPOTENCY_KEY_CONFLICT`, y
`FAILED` nuevo. `ErrorResponse` con `@JsonInclude(NON_NULL)` omite el
campo cuando no hay transferencia asociada.

### 2. `CHAR(3)` vs `VARCHAR(3)` — commit `e3c2693`

**Detectada por:** `ddl-auto: validate` de Hibernate, no por humano ni IA.

El esquema `V1__init.sql` declaraba `currency CHAR(3)`. Hibernate mapea
`CHAR(n)` a `bpchar` en Postgres y la entidad esperaba `varchar(3)`. El
arranque de los tests falló con:

```
Schema validation: wrong column type encountered in column [currency]
in table [accounts]; found [bpchar], but expecting [varchar(3)]
```

**Corrección:** `VARCHAR(3)` en ambas tablas. Es lo idiomático en
Postgres: `CHAR(n)` rellena con espacios y su semántica de comparación
es una trampa ( `'MN' = 'MN '` da `true` ).

**Lección:** la validación del motor atrapó lo que ni el humano ni la
IA vieron al escribir el esquema. `ddl-auto: validate` en lugar de
`create-drop` fue decisión de la spec y pagó.

### 3. `failure_code` faltante en `TransferResponse` — commit `9c5a1e8`

**Detectada por:** la suite de tests al escribir `TransferGetIT`.

El enunciado dice: *"Si es FAILED, incluye la causa"*. El DTO
`TransferResponse` no tenía el campo, así que `GET /transfers/{id}` de
una transferencia fallida devolvía `status: "FAILED"` sin `failure_code`.

**Corrección:** `TransferResponse` y `TransferMapper` agregan
`failure_code`. La transferencia `FAILED` es auditable vía API, no solo
en la base de datos.

## Autocorrecciones durante el proceso

Estas no fueron detectadas por el humano ni por los tests. Fueron
rechazos internos al revisar el propio borrador antes de comprometerlo
al repositorio. Se documentan porque importan para entender el método:

- **Heurística `created_at` → `ExecutionResult.fresh`** (`cb5eef7`).
  El primer borrador del controlador distinguía `201` de `200` con
  `createdAt.isAfter(now - 1s)`. Falla si la transferencia se crea en
  el mismo segundo que el reintento. Se reemplazó por un flag explícito
  que el servicio conoce sin ambigüedad.
- **Autollamada `@Transactional`** (`ca0e52d`). El primer borrador de
  `TransferService` llamaba a sus propios métodos `@Transactional`. En
  Spring la autollamada no pasa por el proxy y la anotación no aplica.
  Se separó la lógica transaccional en `TransferPersistence`, invocado
  a través del proxy.
- **`PageRequest.of(offset/limit, limit)`** (`ca0e52d`). `PageRequest`
  toma número de página, no offset. La conversión solo funciona si el
  offset es múltiplo del limit. Se reemplazó por native query con
  `LIMIT`/`OFFSET` explícitos.

## Límites declarados de este proyecto

- `PENDING` no es observable por API. El modelo es síncrono atómico:
  `PENDING` solo existe dentro de la TX. Justificado en la spec §2.
- No hay reversas ni cancelaciones de transferencias.
- Sin conversión FX; una moneda por transferencia.
- Autenticación con token estático en red privada. Sin rotación, sin
  scopes, sin mTLS. Declarado en la spec §9.
- Sin patrón outbox ni dos fases.

## Sobre el uso de IA

La IA no decidió ninguna regla de negocio. Las reglas vienen del
enunciado y de la spec. La IA contribuyó con: estructura inicial de
módulos, redacción de la spec, borradores de entidades, y generación de
la suite de tests. Todo fue revisado contra la spec y contra el
comportamiento del motor. Las tres correcciones documentadas arriba
muestran que ni la IA ni el humano ni los tests capturan todo solos;
solo la combinación de los cuatro (agente, humano, test, motor)
converge a un sistema correcto.