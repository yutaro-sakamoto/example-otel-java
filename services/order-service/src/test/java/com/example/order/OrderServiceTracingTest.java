package com.example.order;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 計装テスト: OrderService の手動計装(span 発行 + counter 計上)を
 * メモリ内 exporter で検証する。
 *
 * 学習ポイント: OTel SDK は依存性注入しやすい設計になっているので、
 * 本番では Java Agent 経由でグローバルに、テストでは local SDK を渡すことで
 * 計装ロジックそのものを CI で守れる。
 */
class OrderServiceTracingTest {

    private InMemorySpanExporter spanExporter;
    private InMemoryMetricExporter metricExporter;
    private OpenTelemetrySdk sdk;

    @BeforeEach
    void setUp() {
        spanExporter = InMemorySpanExporter.create();
        metricExporter = InMemoryMetricExporter.create();

        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                        .build())
                .setMeterProvider(SdkMeterProvider.builder()
                        .registerMetricReader(PeriodicMetricReader.builder(metricExporter)
                                .setInterval(Duration.ofSeconds(60))
                                .build())
                        .build())
                .build();
    }

    @AfterEach
    void tearDown() {
        sdk.close();
    }

    @Test
    void createOrder_emitsSpanWithExpectedAttributes() {
        PaymentClient paymentClient = Mockito.mock(PaymentClient.class);
        Mockito.when(paymentClient.charge(Mockito.anyString(), Mockito.eq(1200L))).thenReturn("ok");

        OrderService service = new OrderService(paymentClient, sdk);

        OrderController.OrderResponse response = service.createOrder("book", 1200L);

        assertThat(response.paymentStatus()).isEqualTo("ok");
        assertThat(response.item()).isEqualTo("book");

        assertThat(spanExporter.getFinishedSpanItems())
                .hasSize(1)
                .first()
                .satisfies(span -> {
                    assertThat(span.getName()).isEqualTo("OrderService.createOrder");
                    assertThat(span.getAttributes().get(AttributeKey.stringKey("order.item"))).isEqualTo("book");
                    assertThat(span.getAttributes().get(AttributeKey.longKey("order.amount"))).isEqualTo(1200L);
                });
    }

    @Test
    void createOrder_incrementsCounter() {
        PaymentClient paymentClient = Mockito.mock(PaymentClient.class);
        Mockito.when(paymentClient.charge(Mockito.anyString(), Mockito.anyLong())).thenReturn("ok");

        OrderService service = new OrderService(paymentClient, sdk);

        service.createOrder("book", 1200L);
        service.createOrder("book", 800L);
        service.createOrder("pen", 200L);

        // 強制 flush して exporter に流す
        sdk.getSdkMeterProvider().forceFlush().join(2, java.util.concurrent.TimeUnit.SECONDS);

        long total = metricExporter.getFinishedMetricItems().stream()
                .filter(m -> m.getName().equals("orders.created.total"))
                .flatMap(m -> m.getLongSumData().getPoints().stream())
                .mapToLong(p -> p.getValue())
                .sum();

        assertThat(total).isEqualTo(3L);
    }

    @Test
    void createOrder_propagatesPaymentFailureAndMarksSpanError() {
        PaymentClient paymentClient = Mockito.mock(PaymentClient.class);
        Mockito.when(paymentClient.charge(Mockito.anyString(), Mockito.anyLong()))
                .thenThrow(new IllegalStateException("payment declined"));

        OrderService service = new OrderService(paymentClient, sdk);

        try {
            service.createOrder("book", 1200L);
        } catch (IllegalStateException expected) {
            // expected
        }

        assertThat(spanExporter.getFinishedSpanItems())
                .hasSize(1)
                .first()
                .satisfies(span -> {
                    assertThat(span.getStatus().getStatusCode())
                            .isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
                    assertThat(span.getEvents()).isNotEmpty();
                });
    }
}
