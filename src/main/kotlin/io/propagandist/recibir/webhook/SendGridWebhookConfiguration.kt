package io.propagandist.recibir.webhook

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 受信まわりの Bean を組み立てる。
 *
 * **[SendGridSignatureVerifier] に `@Component` を付けない。** あの型は今のところ
 * Spring に依存していない。**このリポジトリは読んで持っていかれることが目的**なので、
 * 検証器は素のまま残し、フレームワークとの接続はここに閉じる。
 */
@Configuration
class SendGridWebhookConfiguration {
    /**
     * 公開鍵を設定から読み、検証器を組み立てる。
     *
     * **既定値を書かない。** 空文字を既定にすると、
     * 鍵の設定を忘れたまま起動でき、**全イベントが 403 で弾かれるのに何も落ちない**状態になる
     * ——README が「最も気づきにくい障害」と呼ぶ形そのものである。
     * 解決できなければ起動が止まるほうがよい。
     */
    @Bean
    fun sendGridSignatureVerifier(
        @Value("\${sendgrid.webhook.public-key}") publicKeyBase64: String,
    ): SendGridSignatureVerifier = SendGridSignatureVerifier(publicKeyBase64)
}
