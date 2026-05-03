# 05. Grafana ツアー — 3シグナルを行き来する

Grafana <http://localhost:3000> (`admin` / `admin`、anonymous でも Admin で入れる) を開いて、トレース/メトリクス/ログを切替えながら使う実践です。

> 事前に `for i in $(seq 1 30); do curl -s -X POST http://localhost:8080/orders -H 'content-type: application/json' -d '{"item":"book","amount":1200}' > /dev/null; done` などで負荷を流しておく。

## datasource 構成

`provisioning/datasources/datasources.yaml` で 3つのデータソースが自動投入されています:

- **Prometheus** (`uid: prometheus`) — 既定。
- **Loki** (`uid: loki`) — `derivedFields` で `trace_id=(\w+)` を Tempo にリンク。
- **Tempo** (`uid: tempo`) — `tracesToLogsV2` と `tracesToMetrics` を有効化。Service Map も使える。

## 1. メトリクスから始める (Prometheus)

`Explore` → datasource を `Prometheus` に切替。

```
sum by (order_item) (rate(orders_created_total[1m]))
```

→ 1秒あたりの注文作成レートが品目別に表示される。

```
histogram_quantile(0.95,
  sum by (le, payment_result) (rate(payment_processing_duration_milliseconds_bucket[5m])))
```

→ 決済処理の p95 レイテンシ。`payment_result="failed"` ラベルで失敗パスのレイテンシも見える。

## 2. トレースで遅さの正体を掴む (Tempo)

`Explore` → datasource を `Tempo` に。

- **Search** タブで `Service Name = order-service`, `Span Name = OrderService.createOrder` をフィルタ。
- 一覧から1本選ぶ → スパンツリーが開く。`order-service` が親、HTTP client → `payment-service` の `PaymentProcessor.process` が子。
- 各 span の右ペインに `Logs for this span` ボタン → クリックで Loki に飛ぶ(`tracesToLogsV2` のおかげ)。
- `Service Graph` (左の `Service` タブ) で呼び出し関係をビジュアル化。Tempo の `metrics_generator` で Prometheus に書き込まれた service-graph metrics を可視化している。

エラーが含まれるトレースを探したい場合は TraceQL の出番:

```
{ status = error }
{ resource.service.name = "payment-service" && status = error }
```

## 3. ログで詳細を読む (Loki)

`Explore` → `Loki` に。

```
{service_name="order-service"}
```

```
{service_name=~"order-service|payment-service"} |= "payment failed"
```

→ ログ行末の `trace_id=...` がリンクになっている (`derivedFields`)。クリックで Tempo に飛び、対応する trace を直接開ける。

## 4. ダッシュボード

`Dashboards` → `OTel Overview` (provisioning で自動投入)。Counter / Histogram / Logs を1画面に並べた最小ダッシュボードです。

## 相関 3 ジャンプを試す (推奨ワークフロー)

学習効果が一番高い操作:

1. Prometheus で `payment_processing_duration_milliseconds_bucket` の p95 が高い時間帯を見つける。
2. その時間帯の Loki で `level=WARN` ログを引く → `trace_id=...` を見つける。
3. クリックで Tempo に飛んで実際の遅い span を眺める。

これが「**3シグナルを trace_id で繋ぐ**」OTel の本領です。

## TraceQL / LogQL / PromQL 早見

| 言語 | 構文の特徴 | このサンプルで効くクエリ |
| --- | --- | --- |
| **PromQL** | 時系列代数 | `rate(orders_created_total[1m])` |
| **LogQL** | `{labels} \|= "phrase"` 型 | `{service_name="order-service"} \|= "order created"` |
| **TraceQL** | `{ attribute = value }` 型 | `{ name = "PaymentProcessor.process" && status = error }` |

次: [06. トラブルシューティング](06-troubleshooting.md)
