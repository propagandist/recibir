# Security Policy

## 脆弱性の報告 / Reporting a Vulnerability

**Issue や Pull Request で報告しないでください。**

GitHub の [Private vulnerability reporting](https://docs.github.com/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)
機能をご利用ください（リポジトリの Security タブから報告できます）。

利用できない場合は問合せフォーム <https://www.propagandist.co.jp/contact/> からご連絡ください。

*Please do not open a public issue. Use GitHub's private vulnerability reporting,
or the contact form at <https://www.propagandist.co.jp/contact/>.*

初回応答の目標は 5 営業日以内です。個人・小規模チームによる運営のため、
即時の対応が難しい場合がある点はご容赦ください。

## 対象範囲

このリポジトリで**特に重視している**箇所です。

- `SendGridSignatureVerifier` — ECDSA 署名検証。誤った署名を通してしまう、
  タイムスタンプ許容範囲の検証が機能しない、といった問題
- `SecurityConfig` / `SendGridOAuthSecurityConfig` — フィルタチェーンの設定ミスにより
  認証を回避できる、意図せず他の経路が公開される、といった問題
- 受信ペイロードの取り扱いに起因する問題（SQL インジェクション、
  過大なペイロードによるリソース枯渇など）

## 対象範囲外

- **利用者側の設定ミス。** 公開鍵の設定漏れ、`permitAll` の適用範囲を広げた改変など
- **リファレンス実装として意図的に省いている部分。** レート制限、WAF、
  監視・アラート、シークレット管理などは環境側の責務としています
- 依存ライブラリの脆弱性そのもの（上流に報告してください）

## 設計上のスタンス

### 署名検証をスキップする仕組みは入れません

ローカル開発では検証を切りたくなりますが、「本番で有効化できてしまう
バイパス経路」を作らない方針です。

開発時に検証を通すには、テスト用の EC P-256 鍵ペアを生成し、
その公開鍵を `sendgrid.webhook.public-key` に設定した上で、
同じ秘密鍵でリクエストに署名してください。

この方針を変更する Pull Request は受け付けていません。

### 検証失敗時に理由を返しません

署名不正・不正 Base64・不正 DER・タイムスタンプ範囲外は、
すべて同一のレスポンス（403、ボディなし）に倒しています。
例外の種類でレスポンスを変えると、攻撃者への情報漏洩になるためです。

ログには記録されるので、調査はサーバ側で行ってください。

## サポート対象バージョン

`develop` ブランチの最新のみです。
過去のタグに対するバックポートは行いません。
