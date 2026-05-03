package com.example.order;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 手動計装のサンプル: Tracer から span を直接組み立て、
 * Meter からカスタムカウンタを記録する。
 *
 * 別の選択肢として `@WithSpan` アノテーションを使う方法もあるが、
 * その場合は OpenTelemetry Java Agent (もしくは Spring Boot Starter の AOP) が必要。
 * 本クラスは単体テストで span 発行を検証できるよう Tracer を直接使う形にしている。
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final AttributeKey<String> ITEM_KEY = AttributeKey.stringKey("order.item");
    private static final AttributeKey<Long> AMOUNT_KEY = AttributeKey.longKey("order.amount");

    private final PaymentClient paymentClient;
    private final Tracer tracer;
    private final LongCounter ordersCreated;

    public OrderService(PaymentClient paymentClient, OpenTelemetry openTelemetry) {
        this.paymentClient = paymentClient;
        this.tracer = openTelemetry.getTracer("com.example.order");
        this.ordersCreated = openTelemetry.getMeter("com.example.order")
                .counterBuilder("orders.created.total")
                .setDescription("Total number of orders created")
                .setUnit("{order}")
                .build();
    }

    public OrderController.OrderResponse createOrder(String item, long amount) {
        Span span = tracer.spanBuilder("OrderService.createOrder")
                .setAttribute(ITEM_KEY, item)
                .setAttribute(AMOUNT_KEY, amount)
                .startSpan();

        try (Scope ignored = span.makeCurrent()) {
            String orderId = UUID.randomUUID().toString();
            log.info("creating order id={} item={} amount={}", orderId, item, amount);

            String paymentStatus = paymentClient.charge(orderId, amount);
            ordersCreated.add(1, Attributes.of(ITEM_KEY, item));
            log.info("order created id={} paymentStatus={}", orderId, paymentStatus);
            return new OrderController.OrderResponse(orderId, item, amount, paymentStatus);
        } catch (RuntimeException ex) {
            span.setStatus(StatusCode.ERROR, ex.getMessage());
            span.recordException(ex);
            log.error("order failed item={} reason={}", item, ex.getMessage());
            throw ex;
        } finally {
            span.end();
        }
    }
}
