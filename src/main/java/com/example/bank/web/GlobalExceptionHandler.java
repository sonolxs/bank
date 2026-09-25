package com.example.bank.web;

import com.example.bank.error.DomainException;
import com.example.bank.error.ErrorCode;
import com.example.bank.web.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ErrorResponse> handleDomain(DomainException ex) {
        ErrorResponse body = new ErrorResponse(
                new ErrorResponse.ErrorBody(ex.getCode().name(), ex.getMessage(), null)
        );
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    /**
     * Payload ilegible (JSON malformado, campo desconocido, tipo incorrecto).
     * Jackson 3 lanza HttpMessageNotReadableException; lo traducimos a 400.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        ErrorResponse body = new ErrorResponse(
                new ErrorResponse.ErrorBody(
                        ErrorCode.VALIDATION_ERROR.name(),
                        "Request body is malformed or contains unknown fields.",
                        null
                )
        );
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Falla de @Valid en DTOs. Solo reportamos el primer error para no
     * filtrar estructura interna.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBeanValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse("Validation failed.");
        ErrorResponse body = new ErrorResponse(
                new ErrorResponse.ErrorBody(ErrorCode.VALIDATION_ERROR.name(), detail, null)
        );
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Falta el header Idempotency-Key. 428 según la spec.
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
        ErrorResponse body = new ErrorResponse(
                new ErrorResponse.ErrorBody(
                        ErrorCode.MALFORMED_IDEMPOTENCY_KEY.name(),
                        "Required header missing: " + ex.getHeaderName(),
                        null
                )
        );
        return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED).body(body);
    }

    /**
     * Falla no controlada. Se loguea el stack, pero NO se filtra al cliente.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnknown(Exception ex, HttpServletRequest req) {
        log.error("Unhandled error on {} {}: {}", req.getMethod(), req.getRequestURI(), ex.toString(), ex);
        ErrorResponse body = new ErrorResponse(
                new ErrorResponse.ErrorBody(
                        ErrorCode.INTERNAL_ERROR.name(),
                        "An unexpected error occurred.",
                        null
                )
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}