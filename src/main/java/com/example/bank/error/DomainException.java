package com.example.bank.error;

import org.springframework.http.HttpStatus;

public class DomainException extends RuntimeException {

    private final ErrorCode code;
    private final HttpStatus status;
    private final String transferId;

    public DomainException(ErrorCode code, HttpStatus status, String message) {
        this(code, status, message, null);
    }

    public DomainException(ErrorCode code, HttpStatus status, String message, String transferId) {
        super(message);
        this.code = code;
        this.status = status;
        this.transferId = transferId;
    }

    public ErrorCode getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getTransferId() {
        return transferId;
    }
}