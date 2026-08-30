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

日次バッチ（03:00 UTC）。以下を**全件**取得し `email_address_state` と突き合わせる。
**期間で絞らない**——取りこぼしは絞った窓の外に落ちたまま、永久に見つからない。

| エンドポイント | 差分と数えるもの | 補完 |
|---|---|---|
| `GET /v3/suppression/spam_reports` | 行が無い、または `sendable = true` | `spam_report` |
| `GET /v3/suppression/bounces` | 同上 | `hard_bounce` |
| `GET /v3/suppression/invalid_emails` | 同上 | `invalid` |
| `GET /v3/suppression/blocks` | **行が無いものだけ** | **書き換えない** |

上から順に見て、先に当たった `reason_code` が残る（§4.4 の優先度に合わせてある）。

差分が出た場合は Webhook の取りこぼしを意味するため、警告ログを出す
（§4.7 の `reconcile.drift`）。**差分が 0 のときは出さない**——毎日出続けると、
監視の側に無視する習慣ができる。

**SendGrid 側を正とする。ただし落とす方向だけである。** SendGrid 側に無いものを
こちらから消さない——抑制リストは運用者が手で外すことがあり、それを
「送ってよくなった」と読むと、**外した瞬間に過去のバウンス先へ送り始める。**

**`blocks` は状態を書き換えない。** §4.4 が `blocked` で `sendable` を落とさないのと
同じ理由で、**一時的な失敗だから**である。落とすと、SendGrid 側が外した日に戻せない。

`last_failure_at` と `soft_bounce_count` にも触らない。どちらも Webhook 由来の値で、
Suppression API の `created` は「リストに載った時刻」である。

### 4.6 セキュリティ設定（`config/`）

フィルタチェーンを分離する。**2 本である。**

| チェーン | 対象 | 方針 |
|---|---|---|
| webhook | `/webhooks/sendgrid/**` | csrf 無効、STATELESS。認証は署名検証が担う |
| app | それ以外 | 認証必須 |

**受信口へ OAuth を足しても、チェーンは増えない。** `securityMatcher` が等しいチェーンを
2 本登録すると、Spring Security が起動時に `UnreachableFilterChainException` で止める
（2026-08-30 実測）。**1 本の中身が変わる**形になる。

| | 通す相手 | 検証 |
|---|---|---|
| OAuth を設定していない | permitAll | 署名のみ |
| OAuth を設定した | authenticated | 署名 ＋ JWT |

**有効かどうかは `JwtDecoder` Bean の有無で決まる。** 設定のキーをこちらで読まない
——Boot が `issuer-uri` / `jwk-set-uri` / `public-key-location` のいずれかから組む。
キー名を実装が持つと、**Boot が読む場所とこちらが見る場所の 2 つになる。**

**推奨は `issuer-uri` である。** あれだけが `iss` クレームの検証を付ける
——`jwk-set-uri` は鍵の在り処しか言わないので、**別の発行者が同じ鍵で署名したトークンを
見分けられない。**

**スコープは要求しない。** 認可サーバは利用者のもので、こちらがスコープ名を決められない。

**認可サーバへ繋がらないときは 403 になる。** `issuer-uri` から作られる decoder は
discovery を最初の検証まで遅延するので**起動は通り**、落ちていることは最初のトークンが
来たときに分かる。そのとき出る例外は `AuthenticationException` ではないため
entry point を通らず、**403 とボディなし**で返る（2026-08-30 実測）。
**署名検証の失敗と同じ番号である。** 捕まえて振り分けてはいない——運用者が見るのは
ERROR のスタックトレースで、SendGrid は非 2xx を再送するため、認可サーバが戻れば取りこぼさない。

OAuth 有効時の `AuthenticationEntryPoint` は、**レスポンスボディに**
`invalid_token` を含めること。ヘッダのみでは SendGrid がトークンを再取得しない。
**ただしトークンが提示されていない要求には含めない**——RFC 6750 §3.1 に従う。
取り直させる相手がいない。

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
| `webhook.rejected` | WARNING | 失敗理由 | 公開鍵・認可サーバの設定ミス / リプレイ / 攻撃 |
| `event.ingested` | INFO | 受信件数、新規件数、種類別内訳 | 重複を除いた実数 |
| `event.unparseable` | WARNING | — | 解釈できないボディ（§4.2 は 200 を返すので他に手段が無い） |
| `address.unsendable` | WARNING | マスク済みアドレス、`reason_code` | バウンス・スパム報告の発生 |
| `reconcile.drift` | WARNING | 差分件数、`source` | **Webhook の取りこぼし量** |

**`webhook.rejected` は署名検証とトークン検証の両方が使う**（§4.6）。どちらも受信口が
弾いたことで、`reason` が見分ける。**行を増やしていない**——運用が条件にするのは
「弾かれているか」であって、内訳はそのあとに絞り込むものである。

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

**アクセストークンも載せない。** 秘密そのものであり、ログへ流すと**保管先が 1 つ増える**。

## 5. 設定

```yaml
sendgrid:
  webhook:
    public-key: ${SENDGRID_WEBHOOK_PUBLIC_KEY}
  api:
    key: ${SENDGRID_API_KEY}      # Suppression API 用
```

設定項目はこれ以上増やさない。

OAuth を使うときだけ、Spring 標準の
`spring.security.oauth2.resourceserver.jwt.issuer-uri` を渡す（§4.6）。
**`sendgrid.*` に専用のフラグを置かない。** 独立した 2 つの設定があると
「有効なのに検証先が無い」状態ができ、**そのときの挙動を別に決めることになる。**

## 6. 非機能

- 受信エンドポイントは 200 を返すまでを短く保つ。DB への INSERT 1 回で切り上げる
- 1 リクエストあたり数百件のイベント配列を想定。ボディサイズ上限を確認すること
- テストは SendGrid アカウント・ネットワークなしで完走すること

## 7. 完成の定義

- [ ] 上記コンポーネントが動作し、テストが green
- [ ] `SendGridSignatureVerifier` のテストが `docs/repository-layout.md` の観点を網羅
- [ ] Testcontainers による DB テストが CI で通る
- [ ] README / SECURITY / LICENSE / NOTICE が揃っている
- [ ] `docs/design.md` に設計判断の理由が記録されている
- [ ] コミット履歴に機密情報が含まれていない
