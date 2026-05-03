package com.example.order;

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

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 統合テスト: Spring Boot コンテキストを起動して /orders を叩き、
 * payment-service への呼び出し部分は PaymentClient のモックで代替する。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "payment.service.url=http://stub")
class OrderControllerIT {

    @Autowired
    MockMvc mockMvc;

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        PaymentClient stubPaymentClient() {
            PaymentClient client = mock(PaymentClient.class);
            when(client.charge(anyString(), anyLong())).thenReturn("ok");
            return client;
        }

        @Bean
        @Primary
        OpenTelemetry testOpenTelemetry() {
            return OpenTelemetry.noop();
        }
    }

    @Test
    void postOrders_returnsOk() throws Exception {
        String body = new ObjectMapper().writeValueAsString(
                new OrderController.OrderRequest("book", 1200L));

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item").value("book"))
                .andExpect(jsonPath("$.amount").value(1200))
                .andExpect(jsonPath("$.paymentStatus").value("ok"));
    }
}
