package com.example.bank.error;

import org.springframework.http.HttpStatus;

/**
 * Excepción de negocio. Lleva el código de error y el status HTTP
 * que el handler global debe usar. Un solo tipo para toda la jerarquía
 * evita proliferación de subclases.
 */
public class DomainException extends RuntimeException {

    private final ErrorCode code;
    private final HttpStatus status;

    public DomainException(ErrorCode code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public ErrorCode getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }
}