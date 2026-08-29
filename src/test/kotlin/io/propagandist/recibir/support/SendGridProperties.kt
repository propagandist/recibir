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
 */
const val TEST_API_KEY = "SG.test-key-not-a-real-one"
