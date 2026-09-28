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

class TransferIdempotencyIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate rest;

    private ResponseEntity<String> post(String key, String body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth("test-token");
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Idempotency-Key", key);
        return rest.postForEntity("/transfers", new HttpEntity<>(body, h), String.class);
    }

    @Test
    void repeatWithSameKeyAndParams_returns200WithoutMovingMoneyAgain() {
        String key = "33333333-3333-4333-8333-000000000001";
        String body = "{\"source_account_id\":\"acc_001\",\"destination_account_id\":\"acc_002\","
                + "\"amount\":\"100.00\",\"currency\":\"MXN\"}";

        ResponseEntity<String> first = post(key, body);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String balanceAfterFirst = rest.exchange("/accounts/acc_001",
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(authOnly()), String.class).getBody();

        ResponseEntity<String> second = post(key, body);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).isEqualTo(first.getBody());

        String balanceAfterSecond = rest.exchange("/accounts/acc_001",
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(authOnly()), String.class).getBody();

        assertThat(balanceAfterSecond).isEqualTo(balanceAfterFirst);
    }

    @Test
    void sameKeyDifferentParams_returns409() {
        String key = "33333333-3333-4333-8333-000000000002";

        post(key, "{\"source_account_id\":\"acc_001\",\"destination_account_id\":\"acc_002\","
                + "\"amount\":\"10.00\",\"currency\":\"MXN\"}");

        ResponseEntity<String> r = post(key,
                "{\"source_account_id\":\"acc_001\",\"destination_account_id\":\"acc_002\","
                        + "\"amount\":\"20.00\",\"currency\":\"MXN\"}");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).contains("IDEMPOTENCY_KEY_CONFLICT");
        assertThat(r.getBody()).contains("transfer_id");
    }

    @Test
    void repeatFailedWithSameKey_reproducesSameErrorWithoutReevaluating() {
        String key = "33333333-3333-4333-8333-000000000003";
        String body = "{\"source_account_id\":\"acc_003\",\"destination_account_id\":\"acc_001\","
                + "\"amount\":\"50000.00\",\"currency\":\"MXN\"}";

        ResponseEntity<String> first = post(key, body);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(first.getBody()).contains("INSUFFICIENT_FUNDS");
        String firstTransferId = extractTransferId(first.getBody());
        assertThat(firstTransferId).isNotBlank();

        // El enunciado prohíbe reevaluar. Repetimos aunque hubiera cambiado el saldo.
        ResponseEntity<String> second = post(key, body);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(second.getBody()).contains("INSUFFICIENT_FUNDS");
        assertThat(extractTransferId(second.getBody())).isEqualTo(firstTransferId);
    }

    private HttpHeaders authOnly() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth("test-token");
        return h;
    }

    private String extractTransferId(String body) {
        int i = body.indexOf("\"transfer_id\":\"");
        if (i < 0) return "";
        int start = i + "\"transfer_id\":\"".length();
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }
}