# SPEC.md — recibir

## 1. 目的

SendGrid の Event Webhook を受信し、メール配信の失敗を確実に検知できる状態を作る。

送信 API の `202 Accepted` は「キューに積んだ」以上の意味を持たず、配信の成否とは
無関係である。特に抑制リスト（過去のバウンス、スパム報告、購読解除）に載った
アドレスへの送信は、API が正常応答を返したまま破棄される（`dropped`）。
この状態は Webhook を受信していない限り検知できない。

## 2. スコープ

### 含む

- Event Webhook の受信（署名検証、冪等な永続化）
- 受信イベントからのアドレス単位の送信可否状態の導出
- Suppression API との日次突き合わせによる取りこぼし補完
- OAuth 2.0（Client Credentials）による認可（オプション、既定は無効）
- 構造化ログの stdout への出力（§4.7）

### 含まない

- メール送信機能そのもの（`custom_args` への `job_id` 埋め込みは
  送信側の責務であり、README で言及するに留める）
- 管理 UI、ダッシュボード、通知
- Inbound Parse Webhook

**「通知」は含まない側に残る。** 上の行が足したのは**ログを出すところまで**である。
どこへ転送し、何を条件に人を呼ぶかは運用側が持つ（§4.7）。
この 2 つを混ぜると、アプリが宛先とレート制限とミュートを抱えることになる。

## 3. データモデル

テーブル間の関係と、外部キー制約を張らない理由は [er.md](er.md) に図で置いた。

### 3.1 `sendgrid_event` — 生イベントログ（append-only）

受信したイベントを解釈せずそのまま保持する。このテーブルが正本であり、
他のすべてのテーブルはここから再構築可能でなければならない。

| カラム | 型 | 備考 |
|---|---|---|
| `id` | BIGSERIAL | PK |
| `sg_event_id` | TEXT NOT NULL | **UNIQUE。冪等キー** |
| `sg_message_id` | TEXT | 接尾辞付きのまま保存 |
| `smtp_id` | TEXT | |
| `email` | TEXT NOT NULL | |
| `event` | TEXT NOT NULL | processed/delivered/bounce/dropped/deferred/spamreport 等 |
| `bounce_type` | TEXT | イベントの `type`。bounce / blocked |
| `reason` | TEXT | |
| `status` | TEXT | SMTP ステータスコード |
| `job_id` | TEXT | `custom_args` 由来 |
| `occurred_at` | TIMESTAMPTZ NOT NULL | SendGrid 側の `timestamp` |
| `received_at` | TIMESTAMPTZ NOT NULL | DEFAULT now() |
| `payload` | JSONB NOT NULL | **受信した生 JSON** |

インデックス: `sg_message_id`、`(email, occurred_at DESC)`、`job_id`、
および失敗系イベントの部分インデックス。

### 3.2 `email_address_state` — 導出状態

「このアドレスに送ってよいか」の唯一の判断材料。
`sendgrid_event` からいつでも再構築できること。

| カラム | 型 | 備考 |
|---|---|---|
| `email` | TEXT | PK |
| `sendable` | BOOLEAN NOT NULL | DEFAULT TRUE |
| `reason_code` | TEXT | hard_bounce / spam_report / unsubscribe / invalid |
| `last_failure_at` | TIMESTAMPTZ | |
| `soft_bounce_count` | INT NOT NULL | DEFAULT 0 |
| `updated_at` | TIMESTAMPTZ NOT NULL | |

### 3.3 `mail_job` — 送信ジョブ

送信側の記録。リファレンス実装としては最小限のスキーマのみ提供する。

## 4. コンポーネント

### 4.1 `SendGridSignatureVerifier`

ECDSA（`SHA256withECDSA`、EC P-256）による署名検証。

- 検証対象は `timestamp(UTF-8 bytes) || rawBody`
- 公開鍵は Base64 の X.509 SubjectPublicKeyInfo。JDK 標準の `KeyFactory("EC")` で読む
- タイムスタンプの許容ずれは 10 分（リプレイ対策）
- **失敗理由を戻り値では返さない。** すべて `false` に倒す
- **ただしログには書く**（§4.7 の `webhook.rejected`）。禁じているのはレスポンスに
  出すことであって、運用者だけが見るログは対象外である。**理由を残さないと、
  公開鍵の設定ミスと攻撃が区別できない**——前者は自分で直せる障害である
- 外部依存なし（BouncyCastle 不要。ログの facade として SLF4J だけを使う）

### 4.2 `SendGridWebhookController`

`POST /webhooks/sendgrid/events`

| 状況 | レスポンス |
|---|---|
| 署名検証成功、永続化成功 | 200 |
| 署名検証失敗 | 403（ボディなし） |
| ペイロードが不正 JSON / 配列でない | 200（§4.7 の `event.unparseable` を出す。再送させても直らない） |
| DB 障害など | 500（再送させる） |

ボディは `ByteArray` で受ける。処理は署名検証と永続化のみ。

### 4.3 `SendGridEventIngestService`

受信配列を `sendgrid_event` へ冪等に一括投入する。

- jOOQ の `dsl.batch(...)` で単一 PreparedStatement に集約
- `onConflict(SG_EVENT_ID).doNothing()`
- `payload` には `JSONB.valueOf(node.toString())`（DTO ではなく生ノード）
- 業務判断は一切行わない

### 4.4 `EventProjector`（`event/`）

`sendgrid_event` から `email_address_state` を再構築する非同期処理。

- **直近に受信したイベント**のアドレスを `@Scheduled` のポーリングで拾う。
  間隔は 1 分、窓は `received_at` で 10 分。月数万通規模ではメッセージキューは不要
- **処理済みを記録しない。** 状態は対象アドレスの全イベントから決まるので、
  同じアドレスを何度作り直しても結果は同じである。**窓が間隔より長いのは無駄ではない**
  ——1 回失敗しても、次の回で拾える
- **単発イベントで上書きしない。** 対象アドレスの全イベントから状態を決定する
- 状態決定の優先度:
  `spamreport` > `bounce`(type=bounce) > `unsubscribe` > `dropped` > `deferred` > `delivered`
- **`unsubscribe` は `dropped` より前に置く。** `dropped` は「抑制リストに載っていたので
  捨てた」という**結果**で、`unsubscribe` はその**原因**である。
  `dropped` しか無いアドレスには `reason_code` を付けない——理由は他のイベントが持つ
- **`deferred` と `blocked`（`bounce` の `type`）では `sendable` を落とさない。**
  どちらも一時的な失敗で、`soft_bounce_count` と `last_failure_at` だけが動く
- **`unsubscribe` では `last_failure_at` を更新しない。** 購読解除は失敗ではない
- **`group_unsubscribe` / `group_resubscribe` は状態の外に置く。**
  グループ単位の購読可否は SendGrid 側が持ち、このテーブルとは粒度が違う
- `--rebuild-all` を付けて起動すると全件を作り直す（解釈ロジック修正時の再処理用）

### 4.5 `SuppressionReconciler`（`reconcile/`）

日次バッチ。以下を取得し `email_address_state` と突き合わせる。

- `GET /v3/suppression/bounces`
- `GET /v3/suppression/blocks`
- `GET /v3/suppression/invalid_emails`
- `GET /v3/suppression/spam_reports`

差分が出た場合は Webhook の取りこぼしを意味するため、警告ログを出す。
SendGrid 側を正とする。

### 4.6 セキュリティ設定（`config/`）

フィルタチェーンを分離する。

| チェーン | 対象 | 方針 |
|---|---|---|
| webhook | `/webhooks/sendgrid/**` | permitAll。csrf 無効、STATELESS。認証は署名検証が担う |
| oauth（任意） | 同上 | `sendgrid.webhook.oauth.enabled=true` のときのみ有効 |
| app | それ以外 | 認証必須 |

OAuth 有効時の `AuthenticationEntryPoint` は、**レスポンスボディに**
`invalid_token` を含めること。ヘッダのみでは SendGrid がトークンを再取得しない。

### 4.7 構造化ログ

1 行の JSON を stdout へ出す。**アプリは外部へ送信しない。** 転送も発報も運用側が持つ
（判断は #1）。形式の定義は `config/CloudLoggingFormat` にあり、出すのは各層である。

`severity` の値は Cloud Logging の `LogSeverity` に合わせる。**`WARN` という値は無く**、
`WARNING` が正式である（2026-08-28 に列挙を確認）。Logback の名前をそのまま出す組み込み形式は
使えず、キー名を変えるだけの設定では値を直せない。時刻は `time` に RFC 3339 で入れる（同日確認）。

下の表は、どのログが何を担うかを示す。**「載せるもの」の欄が空なのは、
出ること自体が情報だから**である。

| `event` | severity | 載せるもの | 何に気づけるか |
|---|---|---|---|
| `webhook.received` | INFO | — | **受信の途絶** |
| `webhook.rejected` | WARNING | 失敗理由 | 公開鍵の設定ミス / リプレイ / 攻撃 |
| `event.ingested` | INFO | 受信件数、新規件数、種類別内訳 | 重複を除いた実数 |
| `event.unparseable` | WARNING | — | 解釈できないボディ（§4.2 は 200 を返すので他に手段が無い） |
| `address.unsendable` | WARNING | マスク済みアドレス、`reason_code` | バウンス・スパム報告の発生 |
| `reconcile.drift` | WARNING | 差分件数、`source` | **Webhook の取りこぼし量** |

**`webhook.received` と `event.ingested` を両方出すのが要点である。** 前者だけでは
再送された重複を実数と読む。後者だけでは、**403 で全部弾かれている状態と、
イベントが全部重複だった状態が同じ 0 に見える。**

**`webhook.received` に件数を載せない。** 受信口は JSON をパースしないので数えられず（§4.2）、
そこへパースを持ち込むと生バイト列を確保する構えが崩れる（§4.1）。
件数は `event.ingested` が持ち、この行は「来ているか」だけを担う。

**受信の途絶は「指標の欠如」で捕まえる。** 一定時間ログが来ていないことを条件にできる
監視基盤があれば、アプリ側の実装は要らない。これが無いと、**通知が来ないことが
「配信が全部成功している」なのか「Webhook が全部弾かれている」なのか区別できない。**

**メールアドレスはマスクする。** ローカル部を伏せ、ドメインは残す（`***@example.com`）。
特定ドメインへの集中的な配信不能をログだけで読むためで、個人の特定は DB を引いて行う。
**ローカル部は 1 文字も残さない**——先頭を残す形にすると、1 文字のローカル部がそのまま全部出る。
**`webhook.rejected` と `event.unparseable` には載せない**——検証を通っていない入力と、
解釈できなかった入力は、何が入っているか分からない。

## 5. 設定

```yaml
sendgrid:
  webhook:
    public-key: ${SENDGRID_WEBHOOK_PUBLIC_KEY}
    oauth:
      enabled: false
  api:
    key: ${SENDGRID_API_KEY}      # Suppression API 用
```

設定項目はこれ以上増やさない。

## 6. 非機能

- 受信エンドポイントは 200 を返すまでを短く保つ。DB への INSERT 1 回で切り上げる
- 1 リクエストあたり数百件のイベント配列を想定。ボディサイズ上限を確認すること
- テストは SendGrid アカウント・ネットワークなしで完走すること

## 7. 完成の定義

- [ ] 上記コンポーネントが動作し、テストが green
- [ ] `SendGridSignatureVerifier` のテストが `docs/repository-layout.md` の観点を網羅
- [ ] Testcontainers による DB テストが CI で通る
- [ ] README / CONTRIBUTING / SECURITY / LICENSE / NOTICE が揃っている
- [ ] `docs/design.md` に設計判断の理由が記録されている
- [ ] コミット履歴に機密情報が含まれていない
