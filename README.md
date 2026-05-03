# example-otel-java

Java アプリケーションの **OpenTelemetry** 対応を学ぶためのサンプルシステム。
`docker compose up --build` 一発で、Spring Boot 製の2サービス + 観測スタック(OTel Collector / Tempo / Prometheus / Loki / Grafana)が立ち上がり、トレース・メトリクス・ログの3シグナルを Grafana で行き来できる。

## 構成

```
 ┌──────────────────┐  HTTP   ┌────────────────────┐
 │  order-service   ├────────▶│  payment-service   │
 │  (Spring Boot)   │         │  (Spring Boot)     │
 └────────┬─────────┘         └─────────┬──────────┘
          │ OTLP gRPC :4317              │
          └──────────────┬───────────────┘
                         ▼
                ┌──────────────────┐
                │  otel-collector  │  受信→処理→分配
                └────────┬─────────┘
        traces  ┌────────┼─────────┐  logs
                ▼        ▼ metrics ▼
          ┌────────┐ ┌──────────┐ ┌──────┐
          │ tempo  │ │prometheus│ │ loki │
          └───┬────┘ └────┬─────┘ └──┬───┘
              └────────┐  │  ┌───────┘
                       ▼  ▼  ▼
                    ┌──────────┐
                    │ grafana  │  http://localhost:3000
                    └──────────┘
```

詳しい解説は [`docs/`](docs/) を参照。

## 前提条件

- Docker Engine 24+ / Docker Compose v2
- (テストをローカル実行する場合のみ) JDK 21 ※ Gradle wrapper 同梱なので別途 Gradle 不要

## クイックスタート

```sh
# 1. ビルド & 全サービス起動 (初回 5〜10 分)
docker compose up --build

# 2. 動作確認 (別ターミナルから)
curl http://localhost:8080/actuator/health      # → {"status":"UP"}
curl http://localhost:8081/actuator/health

# 3. テレメトリーを発生させる (20リクエスト)
for i in $(seq 1 20); do
  curl -s -X POST http://localhost:8080/orders \
    -H 'content-type: application/json' \
    -d '{"item":"book","amount":1200}'
  echo
done

# 4. Grafana を開く
#    http://localhost:3000  (admin / admin、または anonymous で Admin)
```

Grafana で見るべき場所:

| 何を見たいか | 操作 |
| --- | --- |
| 分散トレース | `Explore` → データソース `Tempo` → "Search" タブ → Service Name `order-service` で検索 |
| メトリクス | `Explore` → `Prometheus` → `orders_created_total` や `payment_processing_duration_milliseconds_bucket` |
| ログ | `Explore` → `Loki` → `{service_name="order-service"}` |
| ダッシュボード | `Dashboards` → `OTel Overview` |

## ディレクトリ

```
example-otel-java/
├── docker-compose.yml          全サービス定義(ビルド付き)
├── settings.gradle.kts         Gradle マルチプロジェクト
├── build.gradle.kts            共通設定 (Spring Boot 3.4 / OTel BOM 2.10)
├── services/
│   ├── order-service/          注文サービス (port 8080)
│   └── payment-service/        決済サービス (port 8081)
├── observability/
│   ├── otel-collector/config.yaml
│   ├── tempo/tempo.yaml
│   ├── prometheus/prometheus.yml
│   ├── loki/loki-config.yaml
│   └── grafana/provisioning/   datasource & dashboard 自動投入
└── docs/                       解説ドキュメント (01〜06)
```

## テスト

```sh
./gradlew test
```

- ユニット/計装テスト (`PaymentProcessorTest`, `OrderServiceTracingTest`):
  メモリ内 `InMemorySpanExporter` / `InMemoryMetricExporter` で
  span 発行とカウンタ計上を検証
- 統合テスト (`OrderControllerIT`, `PaymentControllerIT`):
  Spring Boot コンテキストを起動して REST 経路を検証

## 学習トピック早見表

| トピック | 該当ドキュメント |
| --- | --- |
| 全体像と各コンテナの役割 | [docs/01-architecture.md](docs/01-architecture.md) |
| トレース/メトリクス/ログの違い | [docs/02-three-signals.md](docs/02-three-signals.md) |
| Java の自動計装 + 手動計装 | [docs/03-instrumentation.md](docs/03-instrumentation.md) |
| Collector の receivers/processors/exporters | [docs/04-collector-pipeline.md](docs/04-collector-pipeline.md) |
| Grafana で 3 シグナルを行き来する | [docs/05-grafana-tour.md](docs/05-grafana-tour.md) |
| よくある詰まりどころ | [docs/06-troubleshooting.md](docs/06-troubleshooting.md) |

## 後片付け

```sh
docker compose down -v   # コンテナ・ネットワーク・ボリュームを全削除
```
