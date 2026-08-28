package io.propagandist.recibir.webhook

import io.propagandist.recibir.config.SecurityConfig
import io.propagandist.recibir.support.TestKeyPair
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.security.KeyPair
import java.time.Instant
import kotlin.test.assertTrue

/**
 * 受信口の応答を確かめる。対応表の正本は `docs/SPEC.md` §4.2 である。
 *
 * **外部を使わない。** 鍵ペアはその場で生成し、公開鍵を設定へ流し込んで**本番と同じ配線**を通す
 * ——検証器を差し替えると、`SendGridWebhookConfiguration` が繋がっていることを確かめられない。
 *
 * 見ているのは大きく 2 つある。**通ったものが 200 になること**と、
 * **通らなかったものが 403 に揃うこと**である。後者は 400 や 415 と分かれてはいけない。
 */
@WebMvcTest(SendGridWebhookController::class)
@Import(SendGridWebhookConfiguration::class, SecurityConfig::class)
class SendGridWebhookControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    private val timestamp = Instant.now().epochSecond.toString()
    private val body = """[{"email":"user@example.com","event":"delivered","sg_event_id":"abc"}]""".toByteArray()

    /** 既定は「すべて正しい」形で、各テストは崩したい 1 つだけを指定する。 */
    private fun send(
        signature: String? = TestKeyPair.sign(KEY_PAIR, timestamp, body),
        stamp: String? = timestamp,
        payload: ByteArray? = body,
        type: MediaType? = MediaType.APPLICATION_JSON,
    ) = mockMvc.post(SendGridWebhookController.PATH) {
        signature?.let { header(SendGridWebhookController.SIGNATURE_HEADER, it) }
        stamp?.let { header(SendGridWebhookController.TIMESTAMP_HEADER, it) }
        type?.let { contentType = it }
        payload?.let { content = it }
    }

    @Test
    fun `正しい署名なら 200 を返す`() {
        send().andExpect { status { isOk() } }
    }

    @Test
    fun `署名が合わないと 403 で、ボディは空`() {
        val tampered = body.copyOf().also { it[it.lastIndex] = '!'.code.toByte() }
        send(payload = tampered).andExpect {
            status { isForbidden() }
            // 失敗理由を書かない（CLAUDE.md「絶対に変更しないこと」3）
            content { string("") }
        }
    }

    // ここから 3 本は、素朴に実装すると 400 になるところである。
    // 400 と 403 が分かれると、外から入力の種類を当てられる（docs/repository-layout.md「11' を落とせない理由」）。

    @Test
    fun `署名ヘッダが無くても 403 で、400 にならない`() {
        send(signature = null).andExpect { status { isForbidden() } }
    }

    @Test
    fun `timestamp ヘッダが無くても 403 で、400 にならない`() {
        send(stamp = null).andExpect { status { isForbidden() } }
    }

    @Test
    fun `ボディが無くても 403 で、400 にならない`() {
        send(payload = null).andExpect { status { isForbidden() } }
    }

    @Test
    fun `Content-Type が JSON でなくても、落ちるときは 403 で 415 にならない`() {
        val tampered = body.copyOf().also { it[it.lastIndex] = '!'.code.toByte() }
        send(payload = tampered, type = MediaType.TEXT_PLAIN).andExpect { status { isForbidden() } }
    }

    @Test
    fun `Content-Type を見ていないので、署名さえ合えば JSON でなくても通る`() {
        // consumes を絞っていないことの裏返し。SendGrid が何を送っても、判断は署名だけで決まる
        send(type = MediaType.TEXT_PLAIN).andExpect { status { isOk() } }
    }

    @Test
    fun `受信口の外は、認証なしでは通らない`() {
        // webhook チェーン 1 本だけだと、ここが Security を素通りする（config/SecurityConfig）。
        // 素通りするとハンドラが無いので 404 になる——**そこで見分ける**。
        //
        // 番号そのものは固定しない。app チェーンは認証方式を選んでいないので、
        // 返るのは entry point 次第で決まる（2026-08-28 実測では 403）。
        // ここで守りたいのは「ハンドラまで届かないこと」であって、番号ではない。
        val status =
            mockMvc
                .get("/anything")
                .andReturn()
                .response.status
        assertTrue(status in setOf(401, 403), "認証なしで受信口の外へ届いている: $status")
    }

    companion object {
        /** 鍵はテストの中で作る。検証をスキップする経路は作らない（`CLAUDE.md`「絶対に変更しないこと」1）。 */
        val KEY_PAIR: KeyPair = TestKeyPair.generate()

        /**
         * 公開鍵は毎回変わるので、静的なプロパティでは渡せない。
         * ここで流し込むことで、`application.yml` と同じキーから本番と同じ経路で読ませる。
         */
        @JvmStatic
        @DynamicPropertySource
        fun publicKey(registry: DynamicPropertyRegistry) {
            registry.add("sendgrid.webhook.public-key") { TestKeyPair.publicKeyBase64(KEY_PAIR) }
        }
    }
}
