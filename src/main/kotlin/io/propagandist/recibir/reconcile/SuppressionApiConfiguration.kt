package io.propagandist.recibir.reconcile

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * 突き合わせが使う HTTP クライアントを組み立てる。
 *
 * **依存を増やしていない。** `RestClient` は `spring-boot-starter-webmvc` が引く
 * `spring-web` に入っている。HTTP クライアントのライブラリも、テスト用のスタブサーバも足さない。
 *
 * **`RestClient.Builder` を注入していない。** Boot 4 の `spring-boot-starter-webmvc` は
 * その Bean を auto-configure しない——`spring-boot-restclient` が classpath に入らないためで、
 * 注入しようとすると `NoSuchBeanDefinitionException` で起動が止まる（2026-08-29 実測）。
 * **スターターを 1 本足すより、`RestClient.builder()` を自分で呼ぶほうが依存が増えない。**
 */
@Configuration
class SuppressionApiConfiguration {
    /**
     * API キーを設定から読み、クライアントを組み立てる。
     *
     * **既定値を書かない。** 公開鍵と同じ扱いで（`webhook/SendGridWebhookConfiguration`）、
     * 環境変数が無ければ起動が止まる。**「キーが無ければ突き合わせを止める」形にしない**
     * ——設定による挙動の切り替えであり、`CLAUDE.md`「最重要」が禁じている。
     * 止まるなら、起動時に止まるほうが読める。
     */
    @Bean
    fun suppressionApiClient(
        @Value("\${sendgrid.api.key}") apiKey: String,
    ): SuppressionApiClient = SuppressionApiClient(sendGridRestClient(RestClient.builder().requestFactory(timeouts()), apiKey))

    /**
     * 応答を待つ上限。
     *
     * **既定は無制限である。** 日次バッチなので急がないが、上限が無いと
     * SendGrid が応答しない日に**スケジューラのスレッドが戻ってこない**
     * ——[SuppressionApiClient] がページ数に上限を置いたのと同じ理由である。
     *
     * **設定項目にしない**（`docs/SPEC.md` §5）。
     */
    private fun timeouts() =
        SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(10))
            setReadTimeout(Duration.ofSeconds(30))
        }
}

/** SendGrid API の入口。**EU アカウントは `api.eu.sendgrid.com` になる**（2026-08-29 に確認）。 */
internal const val SENDGRID_API_BASE_URL = "https://api.sendgrid.com"

/**
 * 認証済みのクライアントを作る。
 *
 * **関数に切り出してあるのは、テストが同じ組み立てを通せるようにするため**である
 * ——`Authorization` が付くことは、Bean を組み立てる側ではなく**出ていくリクエスト**で
 * 確かめたい。**`requestFactory` はここで触らない**。触ると、テストが差し込むスタブを
 * 上書きしてしまう。
 */
internal fun sendGridRestClient(
    builder: RestClient.Builder,
    apiKey: String,
): RestClient =
    builder
        .baseUrl(SENDGRID_API_BASE_URL)
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer $apiKey")
        .build()
