# 03. Java の計装 — 自動 + 手動

Java で OpenTelemetry を導入する代表的なやり方は3つあります。本リポジトリは **(A) Java Agent による自動計装** と **(C) SDK を直接呼ぶ手動計装** を併用しています。

| 方法 | コード変更 | 利点 | 欠点 |
| --- | --- | --- | --- |
| (A) **OTel Java Agent** (`-javaagent:opentelemetry-javaagent.jar`) | ゼロ | Spring/Servlet/HTTP client/JDBC/JVM ランタイムなど100以上を自動計装 | バイトコード書き換えなので動作の細かい制御は効きづらい |
| (B) **アノテーション**(`@WithSpan` / `@SpanAttribute`) | 数行 | 宣言的で読みやすい | 動かすには Java Agent または Spring Boot Starter (AOP) が必要。素のユニットテストでは効かない |
| (C) **SDK 直接呼出** (`Tracer`/`Meter` を取得して span/metric を作る) | やや多い | 完全に制御可能。テスト容易 | コード量はアノテーションより増える |

## (A) Java Agent — 何がタダで取れるか

`Dockerfile` で次のように埋め込んでいます ([order-service/Dockerfile](../services/order-service/Dockerfile)):

```dockerfile
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.10.0/opentelemetry-javaagent.jar /otel/javaagent.jar
ENTRYPOINT ["java", "-javaagent:/otel/javaagent.jar", "-jar", "/app/app.jar"]
```

`docker-compose.yml` の `OTEL_*` 環境変数だけで、以下が**コード変更ゼロ**で取れます:

- HTTP server span (Spring MVC が受けたリクエスト)
- HTTP client span (`RestClient` で payment-service を呼んだ通信)
- JVM メトリクス (`process.runtime.jvm.memory.used` 等)
- ロガー出力の OTLP ログ化 (`OTEL_INSTRUMENTATION_LOGBACK_APPENDER_*` を有効化)
- Resource 属性 (`OTEL_RESOURCE_ATTRIBUTES=deployment.environment=local,...`)

つまり Tempo を見ると、コードに何も書いていないのに `order-service → payment-service` の親子関係が描かれます。これが Agent の威力。

## (C) 手動計装 — このリポジトリで書いた部分

`OrderService` ([services/order-service/.../OrderService.java](../services/order-service/src/main/java/com/example/order/OrderService.java)):

```java
private final Tracer tracer;
private final LongCounter ordersCreated;

public OrderService(PaymentClient paymentClient, OpenTelemetry openTelemetry) {
    this.tracer = openTelemetry.getTracer("com.example.order");
    this.ordersCreated = openTelemetry.getMeter("com.example.order")
        .counterBuilder("orders.created.total")
        .setDescription("Total number of orders created")
        .setUnit("{order}")
        .build();
}

public OrderResponse createOrder(String item, long amount) {
    Span span = tracer.spanBuilder("OrderService.createOrder")
            .setAttribute(ITEM_KEY, item)
            .setAttribute(AMOUNT_KEY, amount)
            .startSpan();
    try (Scope ignored = span.makeCurrent()) {
        // ... 業務ロジック ...
        ordersCreated.add(1, Attributes.of(ITEM_KEY, item));
        return response;
    } finally {
        span.end();
    }
}
```

ポイント:

1. **`span.makeCurrent()`** で「以降の処理はこの span のスコープ内」と宣言する。これがあるから、内部の `paymentClient.charge(...)` の HTTP client span が**この span の子**として記録される(W3C Trace Context が伝搬する)。
2. **`Attributes.of(ITEM_KEY, item)`** でメトリクスにラベルを付ける。Prometheus 上では `orders_created_total{order_item="book"}` のようなラベル付き時系列になる。
3. **`span.end()`** を必ず呼ぶ — `try-finally` で漏らさない。

`PaymentProcessor` では **Histogram** + **エラー span** + **`recordException`** の練習ができます ([PaymentProcessor.java](../services/payment-service/src/main/java/com/example/payment/PaymentProcessor.java))。

## (B) アノテーションでも書ける、けど…

```java
@WithSpan("OrderService.createOrder")
public OrderResponse createOrder(@SpanAttribute("order.item") String item, ...) { ... }
```

簡潔ですが、これが span を生むのは **Java Agent が走っている時だけ**です。素の `./gradlew test` ではアノテーションは何もしません。

本リポジトリでは「ユニットテストで span が出ることまで検証する」を取るため、(C) の SDK 直接呼出にしています。教材として両方触ってみたい場合は、片方のメソッドだけ `@WithSpan` に書き換えて、コンテナ内で agent が拾うかを観察するのがおすすめです。

## グローバル `OpenTelemetry` の取得

Spring の Bean として登録しているのが [`OrderApplication.java`](../services/order-service/src/main/java/com/example/order/OrderApplication.java) です:

```java
@Bean
public OpenTelemetry openTelemetry() {
    return GlobalOpenTelemetry.get();
}
```

`GlobalOpenTelemetry.get()` は **Java Agent がセットしたグローバル SDK** を返します。Agent なしの環境では noop SDK が返るため、何もせずとも安全に動きます。テストでは `TestConfiguration` で `OpenTelemetry.noop()` を `@Primary` Bean として注入し直しています。

## ログと trace_id の相関

`logback-spring.xml`:

```xml
<pattern>... [trace_id=%X{trace_id:-} span_id=%X{span_id:-}] ...</pattern>
```

Java Agent は SLF4J/Logback の MDC に `trace_id` / `span_id` を自動で挿入します。これでコンソール出力にも、Loki に送信される OTLP ログにも、紐づく trace 情報がつきます。Grafana の Loki datasource では `derivedFields` で `trace_id=(\w+)` を Tempo にリンク化しています。

## まとめ

- 横断的にとにかく取りたい → **Agent**
- 業務ロジックの重要関数を span にしたい → **Agent + 手動 (Tracer 直接 or @WithSpan)**
- 業務 KPI をメトリクス化したい → **Meter で Counter / Histogram**

次: [04. Collector パイプライン](04-collector-pipeline.md)
