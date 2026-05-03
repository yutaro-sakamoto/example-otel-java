# 04. OTel Collector パイプライン

OpenTelemetry Collector は「**受信(receive) → 加工(process) → 送信(export)**」のパイプラインを宣言的に組む中継エージェントです。本リポジトリの設定 [`observability/otel-collector/config.yaml`](../observability/otel-collector/config.yaml) を分解します。

## 4つの登場人物

| パート | 役割 | 例 |
| --- | --- | --- |
| **receivers** | テレメトリーの入口 | `otlp` (gRPC :4317 / HTTP :4318), `prometheus` (scraping) |
| **processors** | 受け取ったデータを加工 | `batch` (まとめ送信), `memory_limiter` (OOM 防止), `attributes` (PII マスキング等) |
| **exporters** | バックエンドへの出口 | `otlp/tempo`, `otlphttp/prometheus`, `otlphttp/loki`, `debug` (stdout) |
| **service.pipelines** | 上記を「signal ごと」に組み合わせる | `traces`, `metrics`, `logs` 各パイプライン |

## 本リポジトリのパイプライン

```yaml
service:
  pipelines:
    traces:
      receivers:  [otlp]
      processors: [memory_limiter, resource, batch]
      exporters:  [otlp/tempo, debug]
    metrics:
      receivers:  [otlp]
      processors: [memory_limiter, resource, batch]
      exporters:  [otlphttp/prometheus, debug]
    logs:
      receivers:  [otlp]
      processors: [memory_limiter, resource, batch]
      exporters:  [otlphttp/loki, debug]
```

3シグナルが**完全に対称**な構成です。OTel の「テレメトリーは形式違いの似た流れ」という設計思想が分かりやすい。

## processors の意義

- **memory_limiter** (必須): メモリ使用率に応じて受信を絞る。これを入れないと急な負荷で OOM Kill されます。
- **batch** (実用上必須): 1件ずつ送らずまとめて送る。バックエンドへの負荷とネットワーク往復を激減させる。
- **resource**: 全テレメトリーに共通属性を追加。本サンプルでは `collector.name=otelcol-contrib` を入れている(運用の出元識別用)。

その他にもよく使う processor:

| processor | 用途 |
| --- | --- |
| `attributes` | 属性のリネーム/削除/マスキング |
| `tail_sampling` | エラー含むトレースだけ残すサンプリング |
| `redaction` | クレカ番号などをマスク |
| `transform` (OTTL) | 高度な式ベース変換 |

## exporters の選び方

OTel Collector 公式版 (`otel/opentelemetry-collector`) は exporter が少なめ。本リポジトリでは `contrib` 版 (`otel/opentelemetry-collector-contrib`) を使い、Loki への OTLP 送信や debug exporter を活用しています。

- `otlp/tempo`: gRPC で Tempo に送る (Tempo は OTLP gRPC ネイティブ)
- `otlphttp/prometheus`: HTTP/protobuf で Prometheus の OTLP エンドポイント (`/api/v1/otlp`) に送る
- `otlphttp/loki`: HTTP で Loki の OTLP エンドポイント (`/otlp`) に送る
- `debug`: 標準出力に dump。トラブル時に超便利 (`docker compose logs otel-collector | grep ...`)

## 自分の様子もメトリクス化

`service.telemetry.metrics.address: 0.0.0.0:8888` で、Collector 自身の動作メトリクス(受信数、エラー数、メモリ等)を Prometheus が `prometheus.yml` の `otel-collector:8888` ジョブで集めています。これも **オブザーバビリティのオブザーバビリティ** という大事な習慣です。Grafana Explore で `otelcol_receiver_accepted_spans_total` などを叩いてみてください。

## デバッグ tips

- 流れているか怪しいとき: `docker compose logs otel-collector | tail -100`(`debug` exporter の verbosity を `detailed` に上げると JSON ダンプが出る)
- 設定ミス時: Collector は起動時に config を validate するので、`docker compose up otel-collector` だけ単体起動するとエラーが見やすい

次: [05. Grafana ツアー](05-grafana-tour.md)
