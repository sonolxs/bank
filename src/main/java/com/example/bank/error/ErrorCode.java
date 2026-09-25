package com.example.bank.error;

/**
 * Enum estable de códigos de error. Se serializa como string en el wire.
 * El orden de este enum NO importa; solo importa el nombre.
 */
public enum ErrorCode {
    // 400
    VALIDATION_ERROR,
    MALFORMED_IDEMPOTENCY_KEY,
    INVALID_CURRENCY,
    SAME_ACCOUNT,
    AMOUNT_NOT_POSITIVE,
    AMOUNT_SCALE_INVALID,
    REFERENCE_TOO_LONG,
    LIMIT_OUT_OF_RANGE,
    // 401
    UNAUTHORIZED,
    // 404
    ACCOUNT_NOT_FOUND,
    // 409
    IDEMPOTENCY_KEY_CONFLICT,
    // 422
    INSUFFICIENT_FUNDS,
    ACCOUNT_NOT_ACTIVE,
    CURRENCY_MISMATCH,
    // 500
    INTERNAL_ERROR
}