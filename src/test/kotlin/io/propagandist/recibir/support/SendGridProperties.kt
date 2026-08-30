package io.propagandist.recibir.support

import org.springframework.test.context.DynamicPropertyRegistry

/**
 * SendGrid まわりの設定を、テストのコンテキストへ流し込む。
 *
 * **どれも既定値を持たない**ので、1 つでも欠けるとコンテキストが起動しない
 * ——**設定の付け忘れを起動時に落とす**ための構えである
 * （`webhook/SendGridWebhookConfiguration` ／ `reconcile/SuppressionApiConfiguration`）。
 * テストの側では、その全部をここが埋める。
 *
 * 署名を見ないテストでも鍵が要るのはこのためで、**各テストがその事情を書く必要は無い。**
 * DB は用途が別なので、要るテストだけが [PostgresContainer] を呼ぶ。
 *
 * @param publicKey 署名を検証するテストは、自分が署名に使った鍵ペアの公開鍵を渡す。
 *   省略すると使い捨ての鍵になる
 */
fun registerSendGridProperties(
    registry: DynamicPropertyRegistry,
    publicKey: String = TestKeyPair.publicKeyBase64(TestKeyPair.generate()),
) {
    registry.add("sendgrid.webhook.public-key") { publicKey }
    registry.add("sendgrid.api.key") { TEST_API_KEY }
}

/**
 * テストが使う API キー。**本物ではない。** 形だけ SendGrid のものに似せてある。
 *
 * ネットワークへは出ない——突き合わせのテストは `MockRestServiceServer` でスタブする。
 *
 * ## 公開前チェックは、必ずここに当たる
 *
 * `docs/repository-layout.md`「履歴」は `SG.` で始まる文字列を探す。
 * **当たるのはこの定数だけである**（2026-08-30 実測。全 94 コミットを走査）。
 * **他が出たら本物を疑う。**
 *
 * **「この行」と書けない。** 行番号は履歴の中で動く——この KDoc を足した
 * コミット自身が 32 行目から 45 行目へ動かした。**数えるのは定数であって行ではない。**
 *
 * **接頭辞を外して当たらなくする案を採らなかった。** 木から消しても**履歴からは消えない**ので、
 * 検査は同じ 1 件を出し続ける。そのうえ**木に無い文字列が履歴だけに残る**形になり、
 * 次に回す人の判断はむしろ難しくなる（#46）。
 *
 * **除外をチェックリストの側へ書くこともしない。** 場所を書けば、この定数が動いた日に腐る。
 * **判断は、検査が当たる場所に置く**——それだけが定数と一緒に動く。
 */
const val TEST_API_KEY = "SG.test-key-not-a-real-one"
