package io.propagandist.recibir.config

import io.propagandist.recibir.support.TestJwt
import io.propagandist.recibir.support.TestKeyPair
import io.propagandist.recibir.support.registerSendGridProperties
import io.propagandist.recibir.webhook.SendGridEventIngestService
import io.propagandist.recibir.webhook.SendGridWebhookConfiguration
import io.propagandist.recibir.webhook.SendGridWebhookController
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.security.KeyPair
import java.time.Instant

/**
 * OAuth を設定していないときの受信口を確かめる（#43）。
 *
 * **こちらが既定の経路である。** [SecurityConfigOAuthTest] との違いは
 * **`JwtDecoder` を置いていないこと 1 つだけ**で、それだけでチェーンの中身が変わる
 * （[SecurityConfig.webhookFilterChain]）。
 *
 * ここが壊れると、**OAuth を使わない全員の受信が止まる。** それでいて有効時のテストは
 * 緑のままなので、分けて置いてある。
 */
@WebMvcTest(SendGridWebhookController::class)
@Import(SendGridWebhookConfiguration::class, SecurityConfig::class)
class SecurityConfigWithoutOAuthTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var ingestService: SendGridEventIngestService

    private val timestamp = Instant.now().epochSecond.toString()
    private val body = """[{"email":"user@example.com","event":"delivered","sg_event_id":"abc"}]""".toByteArray()

    private fun send(token: String? = null) =
        mockMvc.post(SendGridWebhookController.PATH) {
            token?.let { header(HttpHeaders.AUTHORIZATION, "Bearer $it") }
            header(SendGridWebhookController.SIGNATURE_HEADER, TestKeyPair.sign(SIGNING_KEYS, timestamp, body))
            header(SendGridWebhookController.TIMESTAMP_HEADER, timestamp)
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    @Test
    fun `トークンが無くても、署名が合えば 200`() {
        send().andExpect { status { isOk() } }
    }

    @Test
    fun `別の鍵で署名された JWT を付けても 200`() {
        // **誰も検証しないので通る。** 立っていないことは応答から見分けにくいので、
        // 検証されていれば 401 になるはずのトークンが通ることで、それを見る
        send(token = TestJwt.sign(TestJwt.generate())).andExpect { status { isOk() } }
    }

    @Test
    fun `壊れたトークンを付けても 200`() {
        // 上と同じ。**署名検証だけが効いている**状態である
        send(token = "not-a-jwt").andExpect { status { isOk() } }
    }

    companion object {
        val SIGNING_KEYS: KeyPair = TestKeyPair.generate()

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            // issuer-uri も JwtDecoder も置かない。**これが有効時との唯一の違いである**
            registerSendGridProperties(registry, TestKeyPair.publicKeyBase64(SIGNING_KEYS))
        }
    }
}
