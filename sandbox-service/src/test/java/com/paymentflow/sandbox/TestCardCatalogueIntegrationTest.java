package com.paymentflow.sandbox;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M17.1's completion proof: the service boots against real Postgres, Flyway applies
 * both migrations (schema + seed), and the seeded catalogue is readable over real HTTP —
 * not asserted against the repository alone.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TestCardCatalogueIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @Autowired
    private MockMvc mockMvc;

    @Test
    void seededCatalogueIsReadableOverHttp() throws Exception {
        mockMvc.perform(get("/v1/test/cards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(17))
                .andExpect(jsonPath("$[?(@.token == 'pm_card_visa')].outcome").value("APPROVE"))
                .andExpect(jsonPath("$[?(@.token == 'pm_card_chargeDeclined')].declineCode").value("card_declined"))
                .andExpect(jsonPath("$[?(@.token == 'pm_card_processingError')].errorCode").value("processing_error"))
                .andExpect(jsonPath("$[?(@.token == 'pm_card_slow')].latencyMs").value(5000))
                .andExpect(jsonPath("$[?(@.token == 'pm_card_delayedSettlement')].captureBehaviour").value("DEFER"))
                .andExpect(jsonPath("$[?(@.token == 'pm_card_captureFails')].captureBehaviour").value("FAIL"))
                .andExpect(jsonPath("$[?(@.token == 'pm_card_refundFails')].refundBehaviour").value("FAIL"))
                .andExpect(jsonPath("$[?(@.token == 'pm_card_lostCard')].declineCode").value("lost_card"))
                .andExpect(jsonPath("$[?(@.token == 'pm_card_issuerUnavailable')].errorCode").value("issuer_unavailable"));
    }
}
