package com.example.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "payment.failure-rate=0.0",
        "payment.simulate-latency=false"
})
class PaymentControllerIT {

    @Autowired
    MockMvc mockMvc;

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        OpenTelemetry testOpenTelemetry() {
            return OpenTelemetry.noop();
        }
    }

    @Test
    void postPayments_returnsOk() throws Exception {
        String body = new ObjectMapper().writeValueAsString(
                new PaymentController.PaymentRequest("ord-1", 1500L));

        mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("ord-1"))
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.transactionId").exists());
    }
}
