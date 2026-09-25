package com.example.bank.error;

import org.springframework.http.HttpStatus;

/**
 * Errores de forma del payload. Siempre 400.
 * Se separa de DomainException para que el código del servicio
 * distinga "no toqué la base" de "consulté estado y la regla falló".
 */
public class ValidationException extends DomainException {

    public ValidationException(ErrorCode code, String message) {
        super(code, HttpStatus.BAD_REQUEST, message);
    }
}