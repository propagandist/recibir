# CLAUDE.md

recibir — SendGrid Event Webhook 受信のリファレンス実装。

## 最重要 — このプロジェクトの性格

**これはライブラリではなくリファレンス実装です。**

「一つの妥当な設計を、動く形で読めるようにする」ことが目的です。
汎用性を上げる変更は、この目的に対してマイナスに働きます。

以下は**提案しないでください**。良くない案だからではなく、目的と噛み合わないためです。

- 設定による挙動の切り替え（テーブル名、パッケージ名、永続化層の差し替え）
- PostgreSQL 以外の DB への対応、抽象化レイヤーの導入
- Spring Boot 3.x 系への後方互換
- 管理 UI、ダッシュボード、通知チャネルなどの機能追加
- 「将来の拡張に備えた」インターフェース抽出

迷ったら、**分岐を増やさないほう**を選んでください。
読んで理解できることが、設定できることより優先されます。

## 絶対に変更しないこと

1. **署名検証をスキップする経路を作らない。**
   `application-local.yml` であっても、環境変数であっても、プロファイルであっても。
   ローカル開発では、テスト用の EC P-256 鍵ペアを生成して自分で署名する。
2. **リクエストボディは `ByteArray` で受ける。**
   署名検証には生バイト列が必要。`@RequestBody List<SendGridEvent>` にしない。
   ボディを消費するフィルタを追加しない。
3. **署名検証の失敗理由をレスポンスに出さない。**
   署名不正・不正 Base64・不正 DER・タイムスタンプ範囲外はすべて 403、ボディなし。
4. **生 JSON（`payload` カラム）を捨てない。**
   DTO への射影は取り回し用であって、永続化の正本ではない。
5. **単発イベントで状態遷移させない。**
   イベントは重複し、順序も乱れる。導出状態は全イベントから再構築する。

## 技術スタック

| 項目 | バージョン |
|---|---|
| JDK | 25 |
| Kotlin | 2.2.x |
| Spring Boot | 4.1.x |
| jOOQ | 3.20.x（Open Source Edition） |
| PostgreSQL | 16+ |
| Flyway | Spring Boot 管理 |

## Spring Boot 4 の注意点

学習データは Spring Boot 3 系が主流のはずです。以下は 4 系で変わっています。
**Boot 3 のつもりでコードを書かないでください。**

- **Jackson 3 が標準。** `com.fasterxml.jackson.databind` → `tools.jackson.databind`。
  ただし**アノテーションは `com.fasterxml.jackson.annotation` のまま**。
  import を一括置換すると壊れる
- **注入するのは `ObjectMapper` ではなく `JsonMapper`。**
  Boot 4 はフォーマット別マッパーを auto-configure する
- **`JacksonException` は `RuntimeException` 系。**
  Jackson 2 の `JsonProcessingException` は `IOException` を継承していた。
  `catch (e: IOException)` はパースエラーを捕まえない
- **スターター名が変わった。** `spring-boot-starter-web` → `spring-boot-starter-webmvc`
- **Flyway は明示的にスターターが必要。**
  jar が classpath にあるだけでは auto-configure されない
- Kotlin モジュールは `tools.jackson.module:jackson-module-kotlin`

不明な点は推測せず、`./gradlew dependencies` や実際のクラスパスを確認してください。

## ビルドと実行

```bash
docker compose up -d              # PostgreSQL 起動
./gradlew flywayMigrate           # スキーマ適用（codegen の前提）
./gradlew generateJooq            # jOOQ コード生成
./gradlew build                   # ビルド + テスト
./gradlew bootRun
```

**`generateJooq` が通っていないと、`io.propagandist.jooq.*` が存在せず
永続化層は一切コンパイルできません。** 順序を守ってください。

テストは SendGrid アカウントなしで通ること。CI もこの前提です。

## パッケージ構成

```
io.propagandist.recibir
├── webhook/      受信・署名検証・生イベント投入
├── event/        イベント解釈・導出状態の再構築
├── reconcile/    Suppression API との突き合わせ
└── config/       Security、スケジューラ
```

生成コードは `io.propagandist.jooq` 以下（`src` にはコミットしない）。

## コーディング規約

- Kotlin 公式スタイル。`ktlint` に従う
- `-Xjspecify-annotations=strict` を有効にしている。
  Spring の nullable な戻り値を非 null 扱いしない
- 例外メッセージ・ログは英語。**コメントとドキュメントは日本語**
- コメントは「何をしているか」ではなく「**なぜそうしたか**」を書く。
  このリポジトリは読まれることが目的なので、判断の理由が最大の価値
- public な型には KDoc を付ける

## テスト方針

`SendGridSignatureVerifier` は**他のどこよりも厚く**書いてください。
セキュリティコンポーネントを公開する以上、ここのバグは致命的です。

必須の観点は `docs/repository-layout.md` に列挙してあります。
特に「ボディを JSON パースして再シリアライズすると検証に失敗する」ケースは、
生バイト列が必要な理由をテストとして残す意味があるので必ず書いてください。

DB を伴うテストは Testcontainers を使用。

## CI / ワークフロー

GitHub Actions の無料枠 2,000 分/月は **org 全体で共有**。枯らすと全リポジトリの CI と
デプロイが止まる。**ワークフローを増やす・トリガーを変える前に** org の判断規約を読むこと:

`gh api repos/propagandist/.github/contents/docs/ci-strategy.md --jq .content | base64 -d`

**作業の型**（プランを起票で止める／起票の作法／着手前／本文／マージ）は同
`docs/work-conventions.md`（**軸が違う。CI の有無と関係なく効く**）。
**文章の基準**（一貫性・明確さ・リズム・文体・正確さ・網羅性）は同 `docs/writing-baseline.md`
（**同じく軸が違う。人に読ませる文章を書き終えたら読む**）。
**値**（既定ブランチ・ブランチ名・コミット規約・ラベル・merge 方式・表記の対・定番語彙）は
**このリポジトリが持つ**——下の `## 作業の型` と `## 文章の値` に書く。
org 正本には値が 1 つも無い。

## セキュリティ

このリポジトリは**分類 A**（サーバ ＋ DB ＋ 認証）。**認証・認可・出力・秘密の扱い・依存を
変える前に** org の基準を読むこと。**守る値と「崩れる変更」**が項目ごとに書いてある:

`gh api repos/propagandist/.github/contents/docs/security-baseline.md --jq .content | base64 -d`

確かめ方は同 `docs/security-verification.md`（手元／既存ジョブ／週次の 3 層）。
**法務は区分 4**（預からない。リファレンス実装であり、当社の設備が個人データを受け取らない）。
読むのは同 `docs/legal-baseline.md` の **§2 だけ**（**軸が違う。分類とは別に決まる**）。
**区分が変わるのは、これを当社の設備で実運用する日**——そのとき公開文書がゼロ本から始まる。

## 作業の型

org の `work-conventions.md` が正である。**中身をここへ写さない。**

`gh api repos/propagandist/.github/contents/docs/work-conventions.md --jq .content | base64 -d`

同 §6 が「各リポジトリが決めておくこと」を挙げている。recibir の値は以下。
**あちらには書かない**（org §0）。

| 決めるもの | recibir の値 |
|---|---|
| 既定ブランチ名 | `develop`。`main` は v0.1.0 のタグを打つときに作る |
| ブランチの接頭辞 | `feat/` `fix/` `docs/` `chore/` `build/` `test/` `ci/` |
| ブランチ名に issue 番号を入れるか | **入れない。** 何のブランチかは名前で読めるほうがよい |
| コミット subject の言語と型 | 日本語。Conventional Commits の型を前に置く（`feat:` `fix:` `docs:` `build(deps):`） |
| 1 コミットの粒度 | **1 コミット 1 論点。まとめない** |
| merge 方式 | `--no-ff`。マージ済みのブランチは残さない |
| ラベル集合 | `decision` / `enhancement` / `bug` / `documentation` の 4 つだけ。足すときは先にこの表へ書く |
| マイルストーン運用 | **しない。** 実装が始まって版が見えたら決める |
| 本文の書式の見本にする issue 番号 | `#2`。org §2 の 7 項目に答え、受け入れ基準を機械／目視で分けている |
| そのリポジトリ固有の関門 | **public リポジトリ。コミット履歴も公開物で、API キー・実メールアドレス・顧客名を絶対に含めない。** 分類 A（上の「セキュリティ」） |
| Railway へ繋ぐリポジトリ | **`recibir-ops`（private、別リポジトリ）。本体は繋がない**——public な履歴に宛先アドレスが混入すると取り消せない。運用が要求する設定の切り替えは上の「最重要」が禁じている。判断は #1 |

## 文章の値

org の `writing-baseline.md` が正である。**中身をここへ写さない。**

`gh api repos/propagandist/.github/contents/docs/writing-baseline.md --jq .content | base64 -d`

同 §9 が「各リポジトリが決めておくこと」を挙げている。recibir の値は以下。

| 決めるもの | recibir の値 |
|---|---|
| 敬体か常体か | **揃っていない**（2026-08-26 実測）。`docs/SPEC.md` は常体、`CLAUDE.md` と `docs/HANDOVER.md` は指示文が敬体。どちらへ寄せるかは決めていない |
| 表記の対 | **長音を付けない側で一貫している**（2026-08-26 実測: `ヘッダ` 2 / `フィルタ` 2 / `パラメータ` 1、長形は 0 件） |
| 定番語彙と、避ける語の対象外リスト | **決めていない** |
| 一文の長さの閾値 | **置いていない** |
| 基準時点の書式 | `YYYY-MM-DD 実測`。他の形を混ぜない |
| 校正の実装と強度 | **無い。** 検査は手元 |
| 固有の観点 | **このリポジトリは読まれることが目的**。コメントは「なぜそうしたか」を書く（上の「コーディング規約」）。**見出し直後の予告は、続くものが読者に判断を求めるときだけ置く**——図が何を示すか、線引きが何を含まないか。列挙・手順・版の表のように見出しの語で中身が読めるものには置かない |
