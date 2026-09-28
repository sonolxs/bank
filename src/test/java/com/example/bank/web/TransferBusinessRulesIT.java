package com.example.bank.web;

import com.example.bank.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class TransferBusinessRulesIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate rest;

    private ResponseEntity<String> post(String key, String body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth("test-token");
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Idempotency-Key", key);
        return rest.postForEntity("/transfers", new HttpEntity<>(body, h), String.class);
    }

    @Test
    void insufficientFunds_returns422WithTransferId() {
        ResponseEntity<String> r = post("22222222-2222-4222-8222-000000000001",
                "{\"source_account_id\":\"acc_003\",\"destination_account_id\":\"acc_001\","
                        + "\"amount\":\"100.00\",\"currency\":\"MXN\"}");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(r.getBody()).contains("INSUFFICIENT_FUNDS");
        assertThat(r.getBody()).contains("transfer_id");
        assertThat(r.getBody()).doesNotContain("\"transfer_id\":null");
    }

    @Test
    void frozenAccountAsSource_returns422() {
        ResponseEntity<String> r = post("22222222-2222-4222-8222-000000000002",
                "{\"source_account_id\":\"acc_004\",\"destination_account_id\":\"acc_001\","
                        + "\"amount\":\"10.00\",\"currency\":\"MXN\"}");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(r.getBody()).contains("ACCOUNT_NOT_ACTIVE");
    }

    @Test
    void closedAccountAsDestination_returns422() {
        ResponseEntity<String> r = post("22222222-2222-4222-8222-000000000003",
                "{\"source_account_id\":\"acc_001\",\"destination_account_id\":\"acc_005\","
                        + "\"amount\":\"10.00\",\"currency\":\"MXN\"}");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(r.getBody()).contains("ACCOUNT_NOT_ACTIVE");
    }

    @Test
    void currencyMismatch_returns422() {
        ResponseEntity<String> r = post("22222222-2222-4222-8222-000000000004",
                "{\"source_account_id\":\"acc_001\",\"destination_account_id\":\"acc_006\","
                        + "\"amount\":\"10.00\",\"currency\":\"MXN\"}");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(r.getBody()).contains("CURRENCY_MISMATCH");
    }

    @Test
    void sourceAccountNotFound_returns404() {
        ResponseEntity<String> r = post("22222222-2222-4222-8222-000000000005",
                "{\"source_account_id\":\"nope\",\"destination_account_id\":\"acc_001\","
                        + "\"amount\":\"10.00\",\"currency\":\"MXN\"}");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody()).contains("ACCOUNT_NOT_FOUND");
    }
}