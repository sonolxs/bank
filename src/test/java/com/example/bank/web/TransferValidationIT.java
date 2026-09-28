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

class TransferValidationIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate rest;

    private HttpHeaders headers(String idempotencyKey) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth("test-token");
        h.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            h.set("Idempotency-Key", idempotencyKey);
        }
        return h;
    }

    private ResponseEntity<String> post(String key, String body) {
        return rest.postForEntity("/transfers",
                new HttpEntity<>(body, headers(key)), String.class);
    }

    @Test
    void missingIdempotencyKey_returns428() {
        ResponseEntity<String> r = post(null,
                "{\"source_account_id\":\"acc_001\",\"destination_account_id\":\"acc_002\","
                        + "\"amount\":\"10.00\",\"currency\":\"MXN\"}");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
        assertThat(r.getBody()).contains("MALFORMED_IDEMPOTENCY_KEY");
    }

    @Test
    void malformedIdempotencyKey_returns400() {
        ResponseEntity<String> r = post("not-a-uuid",
                "{\"source_account_id\":\"acc_001\",\"destination_account_id\":\"acc_002\","
                        + "\"amount\":\"10.00\",\"currency\":\"MXN\"}");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).contains("MALFORMED_IDEMPOTENCY_KEY");
    }

    @Test
    void amountWithThreeDecimals_returns400() {
        ResponseEntity<String> r = post("11111111-1111-4111-8111-000000000001",
                "{\"source_account_id\":\"acc_001\",\"destination_account_id\":\"acc_002\","
                        + "\"amount\":\"10.005\",\"currency\":\"MXN\"}");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).contains("AMOUNT_SCALE_INVALID");
    }

    @Test
    void amountNegative_returns400() {
        ResponseEntity<String> r = post("11111111-1111-4111-8111-000000000002",
                "{\"source_account_id\":\"acc_001\",\"destination_account_id\":\"acc_002\","
                        + "\"amount\":\"-10.00\",\"currency\":\"MXN\"}");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void amountScientificNotation_returns400() {
        ResponseEntity<String> r = post("11111111-1111-4111-8111-000000000003",
                "{\"source_account_id\":\"acc_001\",\"destination_account_id\":\"acc_002\","
                        + "\"amount\":\"1e3\",\"currency\":\"MXN\"}");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void sameSourceAndDestination_returns400() {
        ResponseEntity<String> r = post("11111111-1111-4111-8111-000000000004",
                "{\"source_account_id\":\"acc_001\",\"destination_account_id\":\"acc_001\","
                        + "\"amount\":\"10.00\",\"currency\":\"MXN\"}");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).contains("SAME_ACCOUNT");
    }

    @Test
    void invalidCurrency_returns400() {
        ResponseEntity<String> r = post("11111111-1111-4111-8111-000000000005",
                "{\"source_account_id\":\"acc_001\",\"destination_account_id\":\"acc_002\","
                        + "\"amount\":\"10.00\",\"currency\":\"EUR\"}");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).contains("INVALID_CURRENCY");
    }

    @Test
    void unknownField_returns400() {
        ResponseEntity<String> r = post("11111111-1111-4111-8111-000000000006",
                "{\"source_account_id\":\"acc_001\",\"destination_account_id\":\"acc_002\","
                        + "\"amount\":\"10.00\",\"currency\":\"MXN\",\"extra\":\"x\"}");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).contains("VALIDATION_ERROR");
    }

    @Test
    void referenceTooLong_returns400() {
        String ref = "x".repeat(141);
        ResponseEntity<String> r = post("11111111-1111-4111-8111-000000000007",
                "{\"source_account_id\":\"acc_001\",\"destination_account_id\":\"acc_002\","
                        + "\"amount\":\"10.00\",\"currency\":\"MXN\",\"reference\":\"" + ref + "\"}");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).contains("REFERENCE_TOO_LONG");
    }
}