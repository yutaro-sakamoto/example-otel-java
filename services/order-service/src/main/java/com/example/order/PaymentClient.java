package com.example.order;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class PaymentClient {

    private final RestClient restClient;

    public PaymentClient(RestClient.Builder builder,
                         @Value("${payment.service.url}") String paymentServiceUrl) {
        this.restClient = builder.baseUrl(paymentServiceUrl).build();
    }

    public String charge(String orderId, long amount) {
        Map<String, Object> response = restClient.post()
                .uri("/payments")
                .body(Map.of("orderId", orderId, "amount", amount))
                .retrieve()
                .body(Map.class);

        if (response == null || response.get("status") == null) {
            throw new IllegalStateException("payment-service returned no status");
        }
        return response.get("status").toString();
    }
}
