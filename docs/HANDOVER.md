# HANDOVER.md — recibir

## 現在地

**どこまで積んだかを、ここには書かない。** 書けば、次にマージした日に嘘になる。

| 知りたいこと | どこを見るか |
|---|---|
| これからやること | <https://github.com/propagandist/recibir/issues>（open のもの） |
| 積んだもの | 閉じた issue と `git log --oneline` |
| 設計 | `docs/SPEC.md` |

下の「作業順序」は**進捗ではなく依存関係である**。
どの項目が済んだかは書いていない。順序を守らないと詰まる箇所を書いてある。

## すでに決まっていること

| 項目 | 決定 | 備考 |
|---|---|---|
| 形態 | リファレンス実装 | ライブラリ化・Starter 化はしない |
| 公開目的 | PROPAGANDIST のブランディング | 利用拡大は非ゴール |
| ライセンス | Apache License 2.0 | 著作権者は PROPAGANDIST CORPORATION |
| DB | PostgreSQL のみ | 他 DB 対応は非ゴール |
| 公開先 | <https://github.com/propagandist/recibir> | public。Apache-2.0。個人アカウントは不可 |
| 記事 | Zenn に投稿 | 記事が主、リポジトリが裏付け |

**公開順序: リポジトリを先、記事を後。**

## 下書きのある成果物

以下は本リポジトリ外に草案がある。取り込んで整合を取ること。

- `SendGridSignatureVerifier.kt` — ほぼそのまま使える
- `SendGridWebhookController.kt` / `SendGridEvent.kt` — 同上
- `SendGridEventIngestService.kt` — jOOQ 生成クラス待ちでコンパイル不可
- `SecurityConfig.kt` / `SendGridOAuthSecurityConfig.kt`
- `schema.sql` — Flyway マイグレーションに変換して使う
- `build.gradle.kts` — codegen 設定を含む雛形

パッケージは `app.propagandist.sendgrid.webhook` で書かれている。
`io.propagandist.recibir.*` に置き換えること。

## 作業順序

**この順序を守らないと詰まります。** 特に 3→4 の依存に注意。

1. **プロジェクト初期化**
   Gradle Kotlin DSL、Spring Boot 4.1、JDK 25 toolchain。
   `./gradlew build` が空プロジェクトとして通ることを先に確認する

2. **`compose.yaml`** — PostgreSQL のみ

3. **Flyway マイグレーション** — `V1__initial_schema.sql`
   `schema.sql` を元にする。`flywayMigrate` が通ること

4. **jOOQ codegen** — `jooqCodegen` を実行
   :::warning
   **3 が完了して DB にスキーマが存在しないと、コード生成は空になります。**
   ここを飛ばして 5 以降に進むと `io.propagandist.jooq.*` が解決できず、
   永続化層が一切コンパイルできません。原因が分かりにくいので必ず先に通すこと。
   :::

5. **`SendGridSignatureVerifier` + テスト** ← 最優先で厚く
   テスト用の EC P-256 鍵ペア生成ヘルパー（`test/support/TestKeyPair.kt`）を
   先に作る。観点は `docs/repository-layout.md` 参照

6. **受信エンドポイント + `SendGridEvent`** — MockMvc テスト付き

7. **`SendGridEventIngestService`** — Testcontainers で冪等性を検証。
   同一 `sg_event_id` を二度投入して 1 行のままであること

   **構造化ログは、ここから各層へ散って入る**（`docs/SPEC.md` §4.7 が形式の正本）。
   **1 つの項目にまとめて番号を振っていない。** 理由は 2 つある——受信・射影・突き合わせの
   **3 か所に分かれる**ので、まとめるとどれか 1 つを入れた時点で済んだように読める。
   そして**既存の番号を動かさない**ため。issue が「作業順序 8」の形で参照しており、
   振り直すと、指している先が黙って別のものになる。

8. **`EventProjector`** — 導出状態の再構築。
   順序が乱れた入力（`delivered` の後に `processed`）でも壊れないテストを書く

9. **`SuppressionReconciler`** — SendGrid API はテストではスタブ化

10. **`SecurityConfig`** — 各チェーンの疎通テスト。
    **webhook と app の 2 本は 6 で先に置いた**（#32）。ここで足すのは OAuth の経路である。
    6 を待たせなかったのは、**Security が既定のままでは受信エンドポイントが
    CSRF と認証で弾かれ、README「動かす」が通らないまま残る**ためである

    **チェーンは増えない**——`securityMatcher` が等しいチェーンは 2 本置けず、
    webhook チェーンの中身が変わる形になる（#43。`docs/SPEC.md` §4.6）

11. **`docs/design.md`** — 設計判断の理由を記録。
    **これはリファレンス実装の中核成果物**。実装のついでではなく独立したタスク

12. **CI（GitHub Actions）** — SendGrid アカウントなしで green になること

13. **公開前チェック** — `docs/repository-layout.md` のチェックリスト

## 詰まりやすいところ

### jOOQ codegen の起動順
上記 4 のとおり。CI でも `flywayMigrate` → `jooqCodegen` の順序が要る。

**codegen 用の DB は `compose.yaml` に寄せました。** Testcontainers はテストの側で使うので、
用途が違い、二重管理にはなりません。

### テストに Docker が要る

投入と受信の結合テストは Testcontainers で PostgreSQL を立てます（#34）。
**Docker が動いていないと `./gradlew build` が通りません。**

`compose.yaml` の DB では代用できません。あちらは codegen 用で、
テストがスキーマを汚すと次の `jooqCodegen` の入力が変わります。

**作業順序 12 で CI を書く日にも効きます。** SendGrid アカウントが要らないという前提は
変わりませんが、**ランナーで Docker が使えること**という前提が増えました。

### Flyway の Gradle プラグインは Gradle 10 で動かなくなる

`flywayMigrate` を打つと `Project.getProperties` の非推奨警告が出ます。
**Gradle 10 ではエラーになります。** プラグインの内部が使っているものなので、
こちらの設定では消せません。**12.4.0 と 13.4.0 の両方で同じ警告が出ました**
（2026-08-27 実測）。

Gradle を 10 へ上げる日には、プラグインの側が直っているかを先に確かめてください。
直っていなければ、codegen 用の DB へスキーマを入れる経路を選び直すことになります。

### Spring Boot 4 と Jackson 3
`ObjectMapper` を注入しようとして Bean が見つからない、
`com.fasterxml.jackson.databind` が解決できない、という形で出ます。
`CLAUDE.md` の該当節を参照。

### 署名検証のローカルテスト
SendGrid は公開 URL にしか POST しません。実接続の確認は
ngrok / Cloudflare Tunnel が必要ですが、**実装とテストは実接続なしで完了できます**。
実接続は最後の疎通確認だけに使ってください。

### `@param:JsonProperty`
Kotlin の data class では use-site target を明示しないと
アノテーションがコンストラクタパラメータに付かないことがあります。

## 未決事項

- [ ] ライセンスヘッダを全ファイルに入れるか、`NOTICE` に集約するか

## やらないこと（再確認）

Claude Code は「親切に」以下を提案してきがちです。**すべて却下してください。**

- 設定項目を増やす、インターフェースを抽出する
- 他 DB への対応、抽象化レイヤー
- 開発用の署名検証スキップフラグ
- Spring Boot 3 系との互換
- 管理 UI、通知機能

判断に迷ったら `CLAUDE.md` の「最重要」節に戻ってください。
