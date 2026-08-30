# design.md — recibir

**この文書はリポジトリ全体を貫く判断だけを持つ。** 4 つある。

読む前に、どこに何があるかを決めておきたい。**個別のコンポーネントの理由はここに無い。**
何を作るかは [SPEC.md](SPEC.md)、テーブル間の関係は [er.md](er.md)、
実装レベルの「なぜそうしたか」は各クラスの KDoc が持つ。**同じことを 2 か所に書かない。**

各判断には**どこにその現れがあるか**を付けた。「そう決めた」だけでは、読む人が確かめられない。
判断からコードへ降りられることが、この文書の役目である。

**却下した案は集めていない。** 正本は閉じた issue で、各節の末尾に番号を置いた。
ここへ集めると、その日以降に足された議論が反映されなくなる。

---

## 1. 受信と解釈を分ける

受信エンドポイントは「壊れずに全部受け取る」ことだけに責任を持つ。
バウンスの判定も、アドレスの状態更新も、すべて後段の非同期処理である。

**200 を返すまでを短く保つためだけではない。** より本質的には、
**単発のイベントで状態を遷移させると必ず壊れる**からである。イベントは重複し、順序も乱れる。
「このアドレスへ送ってよいか」は全イベントを見なければ決まらない——それが判断 3 になる。

この分離は、**外部キー制約を 1 つも張らないところまで及ぶ。** 制約を張ると、親行の不在が
受信の失敗になる。SendGrid は非 2xx を再送するので、**受信側に落ち度が無いのに
再送だけが積み上がる。**

| どこに出ているか | 何が起きているか |
|---|---|
| [`SendGridWebhookController`](../src/main/kotlin/io/propagandist/recibir/webhook/SendGridWebhookController.kt) | 署名を検証し、投入を 1 回呼んで返す。**JSON をパースしない** |
| [`SendGridEventIngestService`](../src/main/kotlin/io/propagandist/recibir/webhook/SendGridEventIngestService.kt) | 投入するだけで、業務判断を一切しない |
| [`V1__initial_schema.sql`](../src/main/resources/db/migration/V1__initial_schema.sql) | 外部キー制約が 1 つも無い。**張り忘れではない** |
| [`EventProjector`](../src/main/kotlin/io/propagandist/recibir/event/EventProjector.kt) | 解釈はここで、受信とは別の時間軸で動く |

分離が崩れる形は 2 つある。**受信口で JSON をパースすること**と、**受信の中で状態を書くこと**。
前者は署名検証に要る生バイト列を失わせ、後者は上の「必ず壊れる」に直結する。

決まった議論: #32（受信エンドポイント） ／ #34（投入）

---

## 2. 生 JSON を正本にする

`sendgrid_event.payload` に、受信した JSON をそのまま置く。
**DTO へ射影した時点で捨てた情報は戻らない。**

SendGrid はフィールドを予告なく足す。生を残しておけば、解釈のバグは後から再処理で直せる
——直せることが判断 3 の前提になっている。

**射影は取り回しのためであって、永続化の正本ではない。**

| どこに出ているか | 何が起きているか |
|---|---|
| [`V1__initial_schema.sql`](../src/main/resources/db/migration/V1__initial_schema.sql) | `payload JSONB NOT NULL`。このテーブルが正本である |
| [`SendGridEventIngestService.insertQuery`](../src/main/kotlin/io/propagandist/recibir/webhook/SendGridEventIngestService.kt) | 受け取ったノードをそのまま入れる。[`SendGridEvent`](../src/main/kotlin/io/propagandist/recibir/webhook/SendGridEvent.kt) を書き戻さない |
| [`SendGridEventIngestServiceTest`](../src/test/kotlin/io/propagandist/recibir/webhook/SendGridEventIngestServiceTest.kt) | 「payload に射影で捨てたキーが残る」——DTO を書き戻していたら、ここで落ちる |

**同じ「生のまま扱う」が、署名検証では別の理由で要る。** あちらは検証対象が生バイト列だからで、
JSON として往復させると空白やキーの順序が変わって一致しなくなる。
**落ちない場合があることのほうが危険**で、それもテストに残してある
（観点の一覧は [repository-layout.md](repository-layout.md)）。

決まった議論: #21（スキーマ） ／ #34（投入）

---

## 3. 導出状態は全イベントから再構築する

`email_address_state` は**捨てて作り直せる二次テーブル**である。
単発のイベントで上書きせず、対象アドレスの全イベントから決める。

イベントは重複し、順序も乱れる。`delivered` の後に `processed` が届くこともある。
だから**時系列で畳み込まない**——状態を決めるのは種別の強さで、時刻は
`last_failure_at` にしか使わない。**一度落ちた `sendable` は、後から届いた `delivered` では戻らない。**

戻す経路が塞がったわけではない。抑制の解除は SendGrid 側が正で、日次の突き合わせが拾う。
**Webhook の取りこぼしは起きる前提**であり、そこも同じテーブルへ入る。

| どこに出ているか | 何が起きているか |
|---|---|
| [`EventProjector`](../src/main/kotlin/io/propagandist/recibir/event/EventProjector.kt) | 直近に受信したアドレスを拾い、そのアドレスの**全イベント**を読み直す |
| [`AddressState`](../src/main/kotlin/io/propagandist/recibir/event/AddressState.kt) | 決定のルールが 1 か所に閉じている。**時刻ではなく種別の強さ**で決まる |
| [`RebuildAllRunner`](../src/main/kotlin/io/propagandist/recibir/event/RebuildAllRunner.kt) | `--rebuild-all` で全件を作り直せる。**解釈を直した日に効く** |
| [`SuppressionReconciler`](../src/main/kotlin/io/propagandist/recibir/reconcile/SuppressionReconciler.kt) | 取りこぼしを外から補い、量をログに出す |
| [`AddressStateTest`](../src/test/kotlin/io/propagandist/recibir/event/AddressStateTest.kt) | 「`delivered` の後に `processed` が届いても壊れない」「`bounce` の後に `delivered` が届いても `sendable` は戻らない」 |

**「捨てて作り直せる」は、書き込む側すべてに制約をかける。** 突き合わせが
`sendgrid_event` から再構築できない値を書けば、その時点でこの性質が失われる。

決まった議論: #36（射影） ／ #40（突き合わせ）

---

## 4. 設定で挙動を切り替えない

**分岐は読む人の負担である。** このリポジトリは読まれることが目的なので、
設定を増やすことと読みやすさは正面から競合する。

それだけではない。**切り替えられる検証は、切り替えた日に意味を失う。**
署名検証をスキップする経路は、プロファイルでも環境変数でも作らない。
リプレイ対策の窓を設定項目にすれば、運用で緩められた日に検証は空になる。

**既定値を置かないことも、この判断の一部である。** 空で起動できるようにすると、
設定を忘れたまま全イベントを 403 で弾き続ける状態が作れてしまう——それは
**最も気づきにくい障害**そのものである。解決できなければ、起動が止まるほうがよい。

| どこに出ているか | 何が起きているか |
|---|---|
| [`SendGridWebhookConfiguration`](../src/main/kotlin/io/propagandist/recibir/webhook/SendGridWebhookConfiguration.kt) | 公開鍵に既定値を置かない。無ければ起動しない |
| [`SendGridSignatureVerifier`](../src/main/kotlin/io/propagandist/recibir/webhook/SendGridSignatureVerifier.kt) | 許容ずれは定数。設定項目にしていない |
| [`SecurityConfig`](../src/main/kotlin/io/propagandist/recibir/config/SecurityConfig.kt) | OAuth の有無を `JwtDecoder` の有無で決める。**有効/無効のフラグを持たない** |
| [`application.yml`](../src/main/resources/application.yml) | 受信に要る設定が数えられる量に収まっている |

**フラグを 1 つ足すと、状態が 2 倍になる。** OAuth では、独立した 2 つの設定
（使うかどうか ／ どこを信じるか）を置くと「有効だが検証先が無い」という状態ができ、
**そのときに何が起きるかを別に決めることになった。** 設定が 1 つなら、立つか立たないかしかない。

決まった議論: #43（OAuth の入口を 1 つに絞った） ／ #5（ログの形式を設定で切り替えない）

---

## この文書を直す契機

- **上の 4 つのどれかが破られた、または破られかけた** — その節の文が判断に使えなかった
  ということである。**文面を直す**（節を増やすのではない）
- **判断の現れが移動した** — 表のファイルパスを直す。**リンク切れは
  [repository-layout.md](repository-layout.md)「公開前に確かめること」が拾う**
- **5 つ目の判断が要ると分かった** — 節を足す前に、既存の 4 つに寄せて読めないかを疑う。
  寄せて判断に使えるなら足さない

**個別のコンポーネントの理由をここへ書かない。** それは [SPEC.md](SPEC.md) と KDoc の仕事で、
写した瞬間から、元を直してもここは直らなくなる。
