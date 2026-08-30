package io.propagandist.recibir.config

import io.propagandist.recibir.support.TestJwt
import io.propagandist.recibir.support.TestKeyPair
import io.propagandist.recibir.support.registerSendGridProperties
import io.propagandist.recibir.support.structuredLogs
import io.propagandist.recibir.webhook.SendGridEventIngestService
import io.propagandist.recibir.webhook.SendGridWebhookConfiguration
import io.propagandist.recibir.webhook.SendGridWebhookController
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.security.KeyPair
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OAuth を設定したときの受信口を確かめる（`docs/SPEC.md` §4.6 ／ #43）。
 *
 * **外部を使わない。** 認可サーバは立てず、RSA 鍵ペアをその場で作って JWT を自己署名し、
 * その公開鍵から組んだ decoder を置く（[TestJwt]）。署名検証の鍵とは**別の鍵ペア**である
 * ——2 つが独立に効いていることを見たいので、片方の鍵で両方が通る形にしない。
 *
 * 見ているのは 3 つある。**トークンを持たないものが通らないこと**、
 * **失敗が SendGrid にトークンを取り直させる形で返ること**、そして
 * **OAuth を通しても署名検証が効いていること**である。
 */
@WebMvcTest(SendGridWebhookController::class)
@Import(
    SendGridWebhookConfiguration::class,
    SecurityConfig::class,
    SecurityConfigOAuthTest.Decoder::class,
)
@ExtendWith(OutputCaptureExtension::class)
class SecurityConfigOAuthTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var ingestService: SendGridEventIngestService

    private val timestamp = Instant.now().epochSecond.toString()
    private val body = """[{"email":"user@example.com","event":"delivered","sg_event_id":"abc"}]""".toByteArray()

    /** 既定は「すべて正しい」形で、各テストは崩したい 1 つだけを指定する。 */
    private fun send(
        token: String? = TestJwt.sign(JWT_KEYS),
        payload: ByteArray = body,
    ) = mockMvc.post(SendGridWebhookController.PATH) {
        token?.let { header(HttpHeaders.AUTHORIZATION, "Bearer $it") }
        header(SendGridWebhookController.SIGNATURE_HEADER, TestKeyPair.sign(SIGNING_KEYS, timestamp, body))
        header(SendGridWebhookController.TIMESTAMP_HEADER, timestamp)
        contentType = MediaType.APPLICATION_JSON
        content = payload
    }

    @Test
    fun `トークンが無いと 401 で、ボディは空`() {
        // RFC 6750 §3.1 は、トークンを送ってこなかった要求に error を含めないと定めている。
        // 取り直させる相手がいないので、ここで invalid_token を出しても意味が無い
        send(token = null).andExpect {
            status { isUnauthorized() }
            content { string("") }
        }
    }

    @Test
    fun `不正なトークンは 401 で、ボディに invalid_token が入る`() {
        // **このテストがこの機能の本体である。** ヘッダだけに書くと SendGrid はトークンを
        // 取り直さず、401 を出し続けたままイベントが失われる（README「踏み抜きやすい箇所」）
        val response = send(token = "not-a-jwt").andReturn().response

        assertEquals(401, response.status)
        assertTrue(response.contentAsString.contains("invalid_token"), response.contentAsString)
    }

    @Test
    fun `期限切れのトークンも 401 で invalid_token`() {
        // SendGrid は取得したトークンをキャッシュして使い回す。**普通に起きるのはこちら**で、
        // ここで取り直させられないと、期限が切れた時点で受信が止まる
        val expired = TestJwt.sign(JWT_KEYS, expiresAt = Instant.now().minusSeconds(600))

        val response = send(token = expired).andReturn().response

        assertEquals(401, response.status)
        assertTrue(response.contentAsString.contains("invalid_token"), response.contentAsString)
    }

    @Test
    fun `正しいトークンと正しい署名なら 200`() {
        send().andExpect { status { isOk() } }
    }

    @Test
    fun `正しいトークンでも、署名が合わなければ 403`() {
        // **OAuth は署名検証の代替ではない**（CLAUDE.md「絶対に変更しないこと」1）。
        // 認可を足したことで検証が緩む経路が無いことを、ここで固定しておく
        val tampered = body.copyOf().also { it[it.lastIndex] = 0x21 }

        send(payload = tampered).andExpect {
            status { isForbidden() }
            content { string("") }
        }
    }

    @Test
    fun `トークンの失敗は webhook_rejected に理由付きで出る`(output: CapturedOutput) {
        // 理由を残さないと、認可サーバの設定ミスと攻撃が区別できない（#5 と同じ判断）。
        // event キーを増やさず、署名検証の失敗と同じキーに相乗りさせている（docs/SPEC.md §4.7）
        send(token = "not-a-jwt").andExpect { status { isUnauthorized() } }

        val rejected = structuredLogs(output, "webhook.rejected").single()
        assertEquals("invalid_token", rejected["reason"].stringValue())
        assertEquals("WARNING", rejected["severity"].stringValue())
    }

    @Test
    fun `トークンが無いときの理由は missing_token`(output: CapturedOutput) {
        // レスポンスは黙るが、ログは黙らない。設定を入れ忘れた側から見えるのはこの行だけである
        send(token = null).andExpect { status { isUnauthorized() } }

        assertEquals("missing_token", structuredLogs(output, "webhook.rejected").single()["reason"].stringValue())
    }

    @Test
    fun `webhook_rejected にトークンそのものを出さない`(output: CapturedOutput) {
        // 検証を通っていない入力は信頼できない（docs/SPEC.md §4.7）。
        // トークンは秘密でもあるので、ログへ流すと保管先が 1 つ増える
        val token = TestJwt.sign(JWT_KEYS, expiresAt = Instant.now().minusSeconds(600))

        send(token = token).andExpect { status { isUnauthorized() } }

        val rejected = structuredLogs(output, "webhook.rejected").single().toString()
        assertTrue(!rejected.contains(token), rejected)
    }

    /**
     * 認可サーバの代わりに置く decoder。
     *
     * **これがあること自体が、OAuth を有効にする条件である**（[SecurityConfig.webhookFilterChain]）。
     * 本番では `issuer-uri` から Boot が組む——auto-configure は
     * `@ConditionalOnMissingBean(JwtDecoder)` で下がるので、**ここが優先されて discovery は
     * 走らない**（2026-08-30 実測）。それ以外の配線は本番と同じものを通している。
     */
    @TestConfiguration
    class Decoder {
        @Bean
        fun jwtDecoder(): JwtDecoder = NimbusJwtDecoder.withPublicKey(TestJwt.publicKey(JWT_KEYS)).build()
    }

    companion object {
        /** 署名検証の鍵（EC）。**OAuth を通しても、こちらが効いている**ことを見る。 */
        val SIGNING_KEYS: KeyPair = TestKeyPair.generate()

        /** トークン検証の鍵（RSA）。認可サーバが持っているはずのもの。 */
        val JWT_KEYS: KeyPair = TestJwt.generate()

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registerSendGridProperties(registry, TestKeyPair.publicKeyBase64(SIGNING_KEYS))
            // 本番で decoder が組まれる経路を、設定の側でも再現しておく
            registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri") { TestJwt.ISSUER }
        }
    }
}
