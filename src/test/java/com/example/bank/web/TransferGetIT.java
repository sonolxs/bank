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

class TransferGetIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate rest;

    private HttpHeaders auth() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth("test-token");
        return h;
    }

    @Test
    void getCompletedTransfer_returnsIt() {
        HttpHeaders h = auth();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Idempotency-Key", "55555555-5555-4555-8555-000000000001");
        ResponseEntity<String> created = rest.postForEntity("/transfers",
                new HttpEntity<>("{\"source_account_id\":\"acc_001\",\"destination_account_id\":\"acc_002\","
                        + "\"amount\":\"1.00\",\"currency\":\"MXN\"}", h),
                String.class);
        String transferId = extractTransferId(created.getBody());

        ResponseEntity<String> r = rest.exchange(
                "/transfers/" + transferId, HttpMethod.GET, new HttpEntity<>(auth()), String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("\"status\":\"COMPLETED\"");
    }

    @Test
    void getFailedTransfer_returnsStatusAndTransferId() {
        HttpHeaders h = auth();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Idempotency-Key", "55555555-5555-4555-8555-000000000002");
        ResponseEntity<String> created = rest.postForEntity("/transfers",
                new HttpEntity<>("{\"source_account_id\":\"acc_003\",\"destination_account_id\":\"acc_001\","
                        + "\"amount\":\"999999.00\",\"currency\":\"MXN\"}", h),
                String.class);
        String transferId = extractTransferId(created.getBody());

        ResponseEntity<String> r = rest.exchange(
                "/transfers/" + transferId, HttpMethod.GET, new HttpEntity<>(auth()), String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("\"status\":\"FAILED\"");
        assertThat(r.getBody()).contains("\"failure_code\":\"INSUFFICIENT_FUNDS\"");
    }

    @Test
    void getUnknownTransfer_returns404() {
        ResponseEntity<String> r = rest.exchange(
                "/transfers/trf_nope", HttpMethod.GET, new HttpEntity<>(auth()), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private String extractTransferId(String body) {
        int i = body.indexOf("\"transfer_id\":\"");
        int start = i + "\"transfer_id\":\"".length();
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }
}