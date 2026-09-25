package com.example.bank;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
/**
 * Base para todos los tests de integración.
 *
 * Un único Postgres 16 compartido por toda la suite (static, no por clase),
 * para no pagar el arranque del contenedor en cada test. Flyway corre
 * en cada arranque del contexto y aplica V1__init.sql, dejando las 8
 * cuentas de la semilla listas para las pruebas.
 *
 * disabledWithoutDocker: si el evaluador no tiene Docker, los tests de
 * integración se saltan con warning en lugar de fallar. Declarado en README.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("bank_test")
                    .withUsername("bank")
                    .withPassword("bank");

    @DynamicPropertySource
    static void registerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}