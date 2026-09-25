package com.example.bank.web;

import com.example.bank.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate rest;

    private HttpHeaders auth() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth("test-token");
        return h;
    }

    private void transfer(String key, String from, String to, String amount) {
        HttpHeaders h = auth();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Idempotency-Key", key);
        String body = "{\"source_account_id\":\"" + from + "\",\"destination_account_id\":\"" + to
                + "\",\"amount\":\"" + amount + "\",\"currency\":\"MXN\"}";
        rest.postForEntity("/transfers", new HttpEntity<>(body, h), String.class);
    }

    @Test
    void historyIncludesBothDirectionsAndOrderedDesc() {
        transfer("44444444-4444-4444-8444-000000000001", "acc_001", "acc_002", "10.00");
        transfer("44444444-4444-4444-8444-000000000002", "acc_002", "acc_001", "5.00");

        ResponseEntity<String> r = rest.exchange(
                "/accounts/acc_001/transfers?limit=50&offset=0",
                HttpMethod.GET, new HttpEntity<>(auth()), String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("\"direction\":\"DEBIT\"");
        assertThat(r.getBody()).contains("\"direction\":\"CREDIT\"");
        assertThat(r.getBody()).contains("\"counterparty_account_id\":\"acc_002\"");
        // El más reciente primero: el segundo transfer (acc_002 -> acc_001) va arriba.
        int idx5 = r.getBody().indexOf("\"amount\":\"5.00\"");
        int idx10 = r.getBody().indexOf("\"amount\":\"10.00\"");
        assertThat(idx5).isLessThan(idx10);
    }

    @Test
    void historyIncludesFailedTransfers() {
        HttpHeaders h = auth();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Idempotency-Key", "44444444-4444-4444-8444-000000000003");
        rest.postForEntity("/transfers",
                new HttpEntity<>("{\"source_account_id\":\"acc_003\",\"destination_account_id\":\"acc_001\","
                        + "\"amount\":\"99999.00\",\"currency\":\"MXN\"}", h),
                String.class);

        ResponseEntity<String> r = rest.exchange(
                "/accounts/acc_003/transfers?limit=50&offset=0",
                HttpMethod.GET, new HttpEntity<>(auth()), String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("\"status\":\"FAILED\"");
        assertThat(r.getBody()).contains("\"direction\":\"DEBIT\"");
    }

    @Test
    void historyLimitAbove100_returns400() {
        ResponseEntity<String> r = rest.exchange(
                "/accounts/acc_001/transfers?limit=200&offset=0",
                HttpMethod.GET, new HttpEntity<>(auth()), String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).contains("LIMIT_OUT_OF_RANGE");
    }

    @Test
    void historyUnknownAccount_returns404() {
        ResponseEntity<String> r = rest.exchange(
                "/accounts/nope/transfers?limit=10&offset=0",
                HttpMethod.GET, new HttpEntity<>(auth()), String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody()).contains("ACCOUNT_NOT_FOUND");
    }
}