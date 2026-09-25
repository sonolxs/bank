package com.example.bank.web;

import com.example.bank.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class AccountControllerIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate rest;

    private HttpHeaders auth() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth("test-token");
        return h;
    }

    @Test
    void getAccount_returnsBalanceAsStringWithTwoDecimals() {
        ResponseEntity<String> resp = rest.exchange(
                "/accounts/acc_008", HttpMethod.GET, new HttpEntity<>(auth()), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("\"balance\":\"1000000000.00\"");
        assertThat(resp.getBody()).contains("\"currency\":\"MXN\"");
        assertThat(resp.getBody()).contains("\"status\":\"ACTIVE\"");
    }

    @Test
    void getAccount_notFound_returns404AndStructuredError() {
        ResponseEntity<String> resp = rest.exchange(
                "/accounts/nope", HttpMethod.GET, new HttpEntity<>(auth()), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).contains("\"code\":\"ACCOUNT_NOT_FOUND\"");
        // No filtra stack traces ni detalle interno.
        assertThat(resp.getBody()).doesNotContain("Exception");
        assertThat(resp.getBody()).doesNotContain("at com.example");
    }

    @Test
    void getAccount_withoutToken_returns401() {
        ResponseEntity<String> resp = rest.exchange(
                "/accounts/acc_001", HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("\"code\":\"UNAUTHORIZED\"");
    }
}