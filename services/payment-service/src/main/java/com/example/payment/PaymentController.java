package com.example.payment;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentProcessor processor;

    public PaymentController(PaymentProcessor processor) {
        this.processor = processor;
    }

    public record PaymentRequest(String orderId, long amount) {}

    public record PaymentResponse(String orderId, String status, String transactionId) {}

    @PostMapping
    public ResponseEntity<PaymentResponse> charge(@RequestBody PaymentRequest request) {
        PaymentResponse response = processor.process(request.orderId(), request.amount());
        return ResponseEntity.ok(response);
    }
}
