package com.example.payment;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 手動計装サンプル(payment 側):
 * - Tracer で span を組み立てる
 * - Histogram に処理時間を記録(成否ラベル付き)
 * - 一定確率で失敗させ、エラースパンと失敗ラベルの記録を学べる
 */
@Service
public class PaymentProcessor {

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessor.class);
    private static final AttributeKey<String> RESULT_KEY = AttributeKey.stringKey("payment.result");
    private static final AttributeKey<String> ORDER_KEY = AttributeKey.stringKey("payment.orderId");
    private static final AttributeKey<Long> AMOUNT_KEY = AttributeKey.longKey("payment.amount");

    private final Tracer tracer;
    private final DoubleHistogram processingDuration;
    private final double failureRate;
    private final boolean useRealLatency;

    public PaymentProcessor(
            OpenTelemetry openTelemetry,
            @Value("${payment.failure-rate:0.1}") double failureRate,
            @Value("${payment.simulate-latency:true}") boolean useRealLatency) {
        this.tracer = openTelemetry.getTracer("com.example.payment");
        this.processingDuration = openTelemetry.getMeter("com.example.payment")
                .histogramBuilder("payment.processing.duration")
                .setDescription("Time spent processing a payment")
                .setUnit("ms")
                .build();
        this.failureRate = failureRate;
        this.useRealLatency = useRealLatency;
    }

    public PaymentController.PaymentResponse process(String orderId, long amount) {
        Span span = tracer.spanBuilder("PaymentProcessor.process")
                .setAttribute(ORDER_KEY, orderId)
                .setAttribute(AMOUNT_KEY, amount)
                .startSpan();

        long start = System.nanoTime();
        try (Scope ignored = span.makeCurrent()) {
            log.info("processing payment orderId={} amount={}", orderId, amount);

            simulateLatency();

            if (ThreadLocalRandom.current().nextDouble() < failureRate) {
                throw new IllegalStateException("payment declined");
            }

            String txId = "tx-" + UUID.randomUUID();
            log.info("payment ok orderId={} tx={}", orderId, txId);
            record(start, "ok");
            return new PaymentController.PaymentResponse(orderId, "ok", txId);
        } catch (RuntimeException ex) {
            span.setStatus(StatusCode.ERROR, ex.getMessage());
            span.recordException(ex);
            log.warn("payment failed orderId={} reason={}", orderId, ex.getMessage());
            record(start, "failed");
            throw ex;
        } finally {
            span.end();
        }
    }

    private void simulateLatency() {
        if (!useRealLatency) {
            return;
        }
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(20, 80));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void record(long startNanos, String result) {
        double durationMs = (System.nanoTime() - startNanos) / 1_000_000.0;
        processingDuration.record(durationMs, Attributes.of(RESULT_KEY, result));
    }
}
