package com.example.bank.domain;

public enum AccountStatus {
    ACTIVE, FROZEN, CLOSED;

    public boolean canOperate() {
        return this == ACTIVE;
    }
}