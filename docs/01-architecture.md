# 01. アーキテクチャ

このリポジトリは「アプリ → OTel Collector → 各バックエンド → Grafana」という、現代的な OpenTelemetry 構成のミニチュアです。

## 全体図

```
 ┌──────────────────┐  HTTP   ┌────────────────────┐
 │  order-service   ├────────▶│  payment-service   │
 │  Spring Boot     │         │  Spring Boot       │
 │  + OTel Agent    │         │  + OTel Agent      │
 └────────┬─────────┘         └─────────┬──────────┘
          │ OTLP gRPC :4317              │
          └──────────────┬───────────────┘
                         ▼
                ┌──────────────────┐
                │  otel-collector  │  受信 → 加工 → 振り分け
                └────────┬─────────┘
        traces  ┌────────┼─────────┐  logs
                ▼        ▼ metrics ▼
          ┌────────┐ ┌──────────┐ ┌──────┐
          │ tempo  │ │prometheus│ │ loki │
          └───┬────┘ └────┬─────┘ └──┬───┘
              └────────┐  │  ┌───────┘
                       ▼  ▼  ▼
                    ┌──────────┐
                    │ grafana  │
                    └──────────┘
```

## 各コンテナの役割

| コンテナ | 役割 | 公開ポート | 主な役割 |
| --- | --- | --- | --- |
| **order-service** | 注文 API。`POST /orders` 受け付け | 8080 | アプリ層 (発信側) |
| **payment-service** | 決済処理。`POST /payments` を提供 | 8081 | アプリ層 (受信側) |
| **otel-collector** | OTLP を受け取り処理してバックエンドへ振り分ける | 4317(gRPC) / 4318(HTTP) / 8888(自己メトリクス) | テレメトリパイプラインの中継 |
| **tempo** | トレース(分散トレース)の保管・クエリ | 3200(query) | バックエンド (traces) |
| **prometheus** | メトリクスの保管・クエリ。OTLP ネイティブ受信 | 9090 | バックエンド (metrics) |
| **loki** | ログの保管・クエリ。OTLP ネイティブ受信 | 3100 | バックエンド (logs) |
| **grafana** | 可視化。3 つのバックエンドへ datasource 接続済み | **3000** | 唯一の UI 入口 |

## なぜ Collector を間に置くのか

「アプリは OTLP で出すだけ」と決めれば、可視化スタックを差し替えてもアプリのコードは無傷で済みます。これが OpenTelemetry の最大の価値:

- 受信プロトコルを統一(OTLP)
- バックエンドごとの差異(Prometheus vs Mimir、Loki vs Elasticsearch、Tempo vs Jaeger)は Collector の **exporter** だけで吸収
- サンプリング、PII マスキング、属性追加といった横断的な処理を Collector で集中管理できる

直送(アプリから直接 Tempo/Prometheus/Loki に送る)構成も技術的には可能ですが、運用に乗せると Collector を入れるのが定石です。学習の最初から「正しい中継地点を置いた構成」に触れておくのがこのサンプルの狙いです。

## モダンな OTLP ネイティブ受信

このサンプルでは Adapter / Sidecar を一切挟んでいません。バックエンドが OTLP を直接受信するモダンな構成:

- **Tempo**: `distributor.receivers.otlp` で OTLP gRPC を直受け
- **Prometheus**: `--web.enable-otlp-receiver` フラグで `/api/v1/otlp/v1/metrics` が有効化される (v2.47+)
- **Loki**: 既定で `/otlp/v1/logs` を持つ (v3.0+)。`limits_config.allow_structured_metadata: true` が必要

そのため `otel-collector/config.yaml` の `exporters` も `otlp/tempo`・`otlphttp/prometheus`・`otlphttp/loki` の3本だけ、と非常にシンプルです。

## 関連ファイル

- [`docker-compose.yml`](../docker-compose.yml)
- [`observability/otel-collector/config.yaml`](../observability/otel-collector/config.yaml)
- [`observability/grafana/provisioning/datasources/datasources.yaml`](../observability/grafana/provisioning/datasources/datasources.yaml)
