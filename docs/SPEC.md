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

### 含まない

- メール送信機能そのもの（`custom_args` への `job_id` 埋め込みは
  送信側の責務であり、README で言及するに留める）
- 管理 UI、ダッシュボード、通知
- Inbound Parse Webhook

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
- **失敗理由を返さない。** すべて `false` に倒す
- 外部依存なし（BouncyCastle 不要）

### 4.2 `SendGridWebhookController`

`POST /webhooks/sendgrid/events`

| 状況 | レスポンス |
|---|---|
| 署名検証成功、永続化成功 | 200 |
| 署名検証失敗 | 403（ボディなし） |
| ペイロードが不正 JSON / 配列でない | 200（退避してアラート。再送させても直らない） |
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

- 未処理イベントのポーリング（`@Scheduled`）で開始する。
  月数万通規模ではメッセージキューは不要
- **単発イベントで上書きしない。** 対象アドレスの全イベントから状態を決定する
- 状態決定の優先度:
  `spamreport` > `bounce`(type=bounce) > `dropped` > `deferred` > `delivered`
- 全件再構築を行うコマンドを用意する（解釈ロジック修正時の再処理用）

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
