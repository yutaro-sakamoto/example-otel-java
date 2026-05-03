package com.example.payment;

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

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentProcessorTest {

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
    void process_success_emitsOkSpanAndHistogram() {
        // failureRate=0.0, レイテンシシミュレートはオフ
        PaymentProcessor processor = new PaymentProcessor(sdk, 0.0, false);

        PaymentController.PaymentResponse res = processor.process("ord-1", 1500L);

        assertThat(res.status()).isEqualTo("ok");
        assertThat(res.transactionId()).startsWith("tx-");

        assertThat(spanExporter.getFinishedSpanItems())
                .hasSize(1)
                .first()
                .satisfies(span -> {
                    assertThat(span.getName()).isEqualTo("PaymentProcessor.process");
                    assertThat(span.getStatus().getStatusCode())
                            .isNotEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
                    assertThat(span.getAttributes().get(AttributeKey.stringKey("payment.orderId")))
                            .isEqualTo("ord-1");
                });

        sdk.getSdkMeterProvider().forceFlush().join(2, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(metricExporter.getFinishedMetricItems())
                .filteredOn(m -> m.getName().equals("payment.processing.duration"))
                .isNotEmpty();
    }

    @Test
    void process_alwaysFails_marksSpanError() {
        // failureRate=1.0 で必ず失敗
        PaymentProcessor processor = new PaymentProcessor(sdk, 1.0, false);

        assertThatThrownBy(() -> processor.process("ord-2", 800L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("declined");

        assertThat(spanExporter.getFinishedSpanItems())
                .hasSize(1)
                .first()
                .satisfies(span -> {
                    assertThat(span.getStatus().getStatusCode())
                            .isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
                    assertThat(span.getEvents()).isNotEmpty(); // recordException
                });

        sdk.getSdkMeterProvider().forceFlush().join(2, java.util.concurrent.TimeUnit.SECONDS);

        // failed ラベルでヒストグラムが記録されている
        boolean hasFailed = metricExporter.getFinishedMetricItems().stream()
                .filter(m -> m.getName().equals("payment.processing.duration"))
                .flatMap(m -> m.getHistogramData().getPoints().stream())
                .anyMatch(p -> "failed".equals(p.getAttributes().get(AttributeKey.stringKey("payment.result"))));
        assertThat(hasFailed).isTrue();
    }
}
