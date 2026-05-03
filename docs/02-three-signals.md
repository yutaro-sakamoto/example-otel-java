# 02. トレース / メトリクス / ログ — 3つのシグナル

OpenTelemetry が扱うのは **3種類のテレメトリーデータ(シグナル)** です。それぞれ「何を答えるための道具か」がはっきり違います。

## 早見表

| シグナル | 形 | 答える質問 | 例 | 本リポジトリの保存先 |
| --- | --- | --- | --- | --- |
| **Trace (トレース)** | リクエスト1本に対応する Span のツリー | 「この遅いリクエストはどこに時間を使った?」 | `POST /orders` → `OrderService.createOrder` → HTTP client → `payment-service`/`PaymentProcessor.process` | **Tempo** |
| **Metric (メトリクス)** | 時系列の数値(Counter / Gauge / Histogram) | 「直近5分の RPS は? 95p レイテンシは?」 | `orders_created_total`, `payment_processing_duration_milliseconds_bucket` | **Prometheus** |
| **Log (ログ)** | 任意の構造化メッセージ | 「あのリクエスト ID で何が起きた? エラー詳細は?」 | `order created id=abc123 paymentStatus=ok` | **Loki** |

## 関連用語

- **Span**: 1つの作業単位(ある関数呼び出し、HTTP リクエストなど)を表す。trace_id と span_id を持つ。
- **Trace**: 同じ trace_id を持つ Span 群。親子関係でツリーを成す。
- **Resource**: 「誰が出したテレメトリーか」を示す不変な属性集合。`service.name=order-service`, `deployment.environment=local` など。
- **Attribute**: Span / Metric / Log それぞれにぶら下がる KV ペア。`order.item=book`, `payment.result=ok` など。
- **Instrument** (メトリクス): 計測の口。Counter(単調増加)、UpDownCounter、Histogram(分布)、ObservableGauge など。

## 3シグナルの相関 (このリポジトリでの実例)

3シグナルは独立に見るより、**相関** させると真価が出ます。`trace_id` がノリ役。

1. **Trace → Logs**: Tempo で遅いトレースを開く → 右ペインの「Logs for this span」から Loki に飛ぶ → そのスパンと同じ trace_id を持つログ行が表示される。
2. **Logs → Trace**: Loki で `level=ERROR` を検索 → ログ本文中の `trace_id=...` がリンク化されている(`derivedFields` で設定済み) → クリックで Tempo に飛ぶ。
3. **Trace → Metrics**: Tempo の Service Graph から呼び出し関係と RED 指標(Rate / Error / Duration) を Prometheus 経由で表示。

これを成立させるための仕掛けがコードにも仕込んであります:

- `logback-spring.xml` の `%X{trace_id}` MDC 出力 ([`order-service/src/main/resources/logback-spring.xml`](../services/order-service/src/main/resources/logback-spring.xml))
- Grafana 側の datasource 設定 (`tracesToLogsV2`, `derivedFields`) — [`datasources.yaml`](../observability/grafana/provisioning/datasources/datasources.yaml)

## どれを使うか?

「**まずメトリクスで異常を検知 → トレースで遅さの原因を絞り込み → ログで詳細を読む**」という順番が王道です。3つ揃って初めて「気づく → 突き止める → 解決する」が回ります。

次は: [03. Java での計装](03-instrumentation.md)
