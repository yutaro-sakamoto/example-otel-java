# 06. トラブルシューティング

学習中によく遭遇する詰まりどころと、確認の順序です。

## ポートが衝突する

`docker compose up` で「port is already allocated」が出る場合、別プロセスが下記を使っています:

| 既定ポート | 用途 |
| --- | --- |
| 3000 | Grafana |
| 3100 | Loki |
| 3200 | Tempo |
| 4317 | OTel Collector OTLP gRPC |
| 4318 | OTel Collector OTLP HTTP |
| 8080 | order-service |
| 8081 | payment-service |
| 8888 | Collector self metrics |
| 9090 | Prometheus |

対処: `docker compose down` で他のデモ系を落とすか、`docker-compose.yml` の `ports:` 左側を別の番号に変える(例: `"13000:3000"`)。

## Grafana にデータが見えない

順序立ててチェック:

1. **アプリは生きているか**
   ```
   curl http://localhost:8080/actuator/health
   curl http://localhost:8081/actuator/health
   ```
2. **トラフィックを発生させたか** — 何もリクエストがなければ何も見えません:
   ```
   curl -X POST http://localhost:8080/orders -H 'content-type: application/json' -d '{"item":"book","amount":1200}'
   ```
3. **Collector が受信できているか** — `debug` exporter のログを確認:
   ```
   docker compose logs otel-collector | grep -E "TracesExporter|MetricsExporter|LogsExporter"
   ```
4. **Collector からバックエンドへ送れているか** — エラーが出るとここで分かる:
   ```
   docker compose logs otel-collector | grep -i "error\|refused\|fail"
   ```
5. **各バックエンドの状態**:
   ```
   docker compose logs tempo prometheus loki | tail -50
   ```

## Java Agent がダウンロードできない (ビルド失敗)

`docker build` 中に `opentelemetry-javaagent.jar` の `ADD` でネットワークエラーが出ることがあります。社内プロキシ環境では:

```dockerfile
# https_proxy を build-arg で渡すか、
# あらかじめローカルに jar をダウンロードして COPY で配置する
COPY opentelemetry-javaagent.jar /otel/javaagent.jar
```

## トレースは見えるがログが見えない

`docker compose logs loki` に `error: structured metadata not allowed` が出ていれば、`loki-config.yaml` の `limits_config.allow_structured_metadata: true` が抜けています。本リポジトリの設定では既に有効化済み。

## メトリクスは見えるが分布(Histogram)が出ない

OTLP は **delta temporality** をデフォルト出力する Java SDK ですが、Prometheus は **cumulative temporality** が前提です。本リポジトリでは Prometheus の OTLP 受信が delta→cumulative の変換を行うため動きますが、もし `histogram_quantile` が空なら:

```sh
docker compose exec prometheus wget -qO- "http://localhost:9090/api/v1/query?query=payment_processing_duration_milliseconds_bucket"
```

を叩いて生メトリクスがあるか確認します。

## OTel デバッグログを出す

Java Agent 側の挙動を詳しく追いたい場合、`docker-compose.yml` の対象サービスに環境変数を追加:

```yaml
environment:
  OTEL_LOG_LEVEL: debug
  OTEL_JAVAAGENT_DEBUG: "true"
```

→ 起動時に「どの instrumentation が有効か」「どこに OTLP を送っているか」が一覧出力されます。

## アプリ起動が遅い (10秒以上)

JVM のコールドスタート + Spring Boot 起動 + Java Agent ロード で初回 8〜15秒は普通です。`docker compose up -d` でデタッチ起動 → `docker compose logs -f order-service` で進捗を見るのが楽。

## まっさらに戻したい

```sh
docker compose down -v        # コンテナ + ネットワーク + ボリューム削除
docker compose build --no-cache  # キャッシュ無視で再ビルド
```

`./gradlew clean` で Java 側の build 出力もクリーンに戻せます。
