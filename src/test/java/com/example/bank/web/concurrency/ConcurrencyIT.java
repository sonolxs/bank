package com.example.bank.concurrency;

import com.example.bank.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate rest;

    @Test
    void concurrentBidirectionalTransfers_preserveMoneyAndNeverGoNegative() throws Exception {
        long initialA = balanceOf("acc_001");
        long initialB = balanceOf("acc_002");
        long totalInitial = initialA + initialB;

        int threads = 20;
        long amountCents = 100_00L; // 100.00 MXN en centavos

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(threads);

        AtomicInteger completed = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                // Alternar dirección: pares A->B, impares B->A.
                String from = (idx <15) ? "acc_001" : "acc_002";
                String to   = (idx <15) ? "acc_002" : "acc_001";

                String key = String.format("66666666-6666-4666-8666-%012d", idx);
                String body = "{\"source_account_id\":\"" + from + "\",\"destination_account_id\":\"" + to
                        + "\",\"amount\":\"100.00\",\"currency\":\"MXN\"}";

                HttpHeaders h = new HttpHeaders();
                h.setBearerAuth("test-token");
                h.setContentType(MediaType.APPLICATION_JSON);
                h.set("Idempotency-Key", key);

                ResponseEntity<String> resp = rest.postForEntity("/transfers",
                        new HttpEntity<>(body, h), String.class);

                int code = resp.getStatusCode().value();
                if (code == 201) {
                    completed.incrementAndGet();
                } else if (code == 422 && resp.getBody() != null
                        && resp.getBody().contains("INSUFFICIENT_FUNDS")) {
                    insufficient.incrementAndGet();
                } else {
                    other.incrementAndGet();
                    System.err.println("Unexpected response: " + code + " " + resp.getBody());
                }
            }));
        }

        // Liberar a todos los hilos al mismo instante.
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();

        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        // ---------------------------
        // Aserciones del invariante
        // ---------------------------

        long finalA = balanceOf("acc_001");
        long finalB = balanceOf("acc_002");
        long totalFinal = finalA + finalB;

        // 1. Conservación: el dinero total es el mismo antes y después.
        assertThat(totalFinal)
                .as("Conservación de dinero: totalFinal debe ser igual a totalInitial")
                .isEqualTo(totalInitial);

        // 2. No-negatividad: ningún saldo terminó por debajo de cero.
        assertThat(finalA).as("acc_001 no debe quedar negativa").isGreaterThanOrEqualTo(0);
        assertThat(finalB).as("acc_002 no debe quedar negativa").isGreaterThanOrEqualTo(0);

        // 3. Todos los intentos terminaron en COMPLETED o INSUFFICIENT_FUNDS.
        assertThat(other.get())
                .as("No debe haber respuestas inesperadas (500, 409, etc.)")
                .isZero();
        assertThat(completed.get() + insufficient.get()).isEqualTo(threads);

        System.out.println("Concurrency test: completed=" + completed.get()
                + " insufficient=" + insufficient.get()
                + " initialA=" + initialA + " finalA=" + finalA
                + " initialB=" + initialB + " finalB=" + finalB);
    }

    /**
     * Consulta el saldo de una cuenta vía la API y lo convierte a centavos.
     * El wire devuelve "1500.00"; lo parseamos a long sin usar float.
     */
    private long balanceOf(String accountId) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth("test-token");
        ResponseEntity<String> resp = rest.exchange(
                "/accounts/" + accountId, HttpMethod.GET, new HttpEntity<>(h), String.class);

        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("GET /accounts/%s debe responder 2xx", accountId)
                .isTrue();

        String body = resp.getBody();
        assertThat(body).isNotNull();

        // Extraer el valor de "balance":"<num>"
        int i = body.indexOf("\"balance\":\"");
        assertThat(i).as("Debe existir el campo balance").isGreaterThanOrEqualTo(0);
        int start = i + "\"balance\":\"".length();
        int end = body.indexOf('"', start);
        String raw = body.substring(start, end);

        // Parsear "1500.00" -> 150000 sin float.
        int dot = raw.indexOf('.');
        String intPart = raw.substring(0, dot);
        String decPart = raw.substring(dot + 1);
        return Long.parseLong(intPart) * 100 + Long.parseLong(decPart);
    }
}