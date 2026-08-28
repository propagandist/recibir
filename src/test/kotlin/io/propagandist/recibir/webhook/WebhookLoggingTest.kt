package io.propagandist.recibir.webhook

import io.propagandist.jooq.Tables.SENDGRID_EVENT
import io.propagandist.recibir.support.PostgresContainer
import io.propagandist.recibir.support.TestKeyPair
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.security.KeyPair
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 受信経路が出す構造化ログを、実際の出力で確かめる（#5）。
 *
 * `SendGridWebhookIngestTest` が DB を見るのに対し、**ここは stdout を見る。**
 * 分けたのは観点が違うためである——あちらは「入ったか」、こちらは
 * **「入らなかったことに気づけるか」**を見る。
 *
 * DB は Testcontainers が立てる。**Docker が要る。**
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
class WebhookLoggingTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dsl: DSLContext

    @Autowired
    private lateinit var jsonMapper: JsonMapper

    @BeforeEach
    fun clean() {
        dsl.deleteFrom(SENDGRID_EVENT).execute()
    }

    /** `event` が一致する行だけを、出た順に取り出す。 */
    private fun entries(
        output: CapturedOutput,
        event: String,
    ): List<JsonNode> =
        output.all
            .lineSequence()
            .filter { it.startsWith("{") }
            .map { jsonMapper.readTree(it) }
            .filter { it["event"]?.stringValue() == event }
            .toList()

    private fun send(
        payload: String,
        stamp: String = Instant.now().epochSecond.toString(),
        body: String = payload,
    ) = mockMvc.post(SendGridWebhookController.PATH) {
        header(SendGridWebhookController.SIGNATURE_HEADER, TestKeyPair.sign(KEY_PAIR, stamp, payload.toByteArray()))
        header(SendGridWebhookController.TIMESTAMP_HEADER, stamp)
        contentType = MediaType.APPLICATION_JSON
        content = body.toByteArray()
    }

    @Test
    fun `署名を通ると webhook_received が 1 行出る`(output: CapturedOutput) {
        // 受信の途絶は、この行が来なくなることで捕まえる（#5）
        send(DELIVERED).andExpect { status { isOk() } }

        assertEquals(1, entries(output, "webhook.received").size)
    }

    @Test
    fun `署名が合わないと理由付きで webhook_rejected が出て、レスポンスは空`(output: CapturedOutput) {
        send(DELIVERED, body = "${DELIVERED}X").andExpect {
            status { isForbidden() }
            // 理由はログにだけ書く（CLAUDE.md「絶対に変更しないこと」3）
            content { string("") }
        }

        val rejected = entries(output, "webhook.rejected").single()
        assertEquals("signature_mismatch", rejected["reason"].stringValue())
        // 受信そのものが成立していないので、この行は出ない
        assertTrue(entries(output, "webhook.received").isEmpty())
    }

    @Test
    fun `webhook_rejected にボディもメールアドレスも出ない`(output: CapturedOutput) {
        // 検証を通っていない入力は信頼できない。ログへ流すと、そこが汚れる経路になる
        send(DELIVERED, body = "${DELIVERED}X").andExpect { status { isForbidden() } }

        val rejected = entries(output, "webhook.rejected").single().toString()
        assertFalse(rejected.contains("user@example.com"), rejected)
        assertFalse(rejected.contains("sg_event_id"), rejected)
    }

    @Test
    fun `ヘッダが無いときの理由は missing_header`(output: CapturedOutput) {
        // 検証器まで届かない経路。ここで黙ると、403 の理由が 1 つだけ闇に落ちる
        mockMvc
            .post(SendGridWebhookController.PATH) {
                contentType = MediaType.APPLICATION_JSON
                content = DELIVERED.toByteArray()
            }.andExpect { status { isForbidden() } }

        assertEquals("missing_header", entries(output, "webhook.rejected").single()["reason"].stringValue())
    }

    @Test
    fun `タイムスタンプがずれているときの理由は timestamp_out_of_range`(output: CapturedOutput) {
        // 署名は正しい。サーバの時計がずれた日に、これが出ていれば原因が読める
        val old = (Instant.now().epochSecond - 3600).toString()

        send(DELIVERED, stamp = old).andExpect { status { isForbidden() } }

        assertEquals(
            "timestamp_out_of_range",
            entries(output, "webhook.rejected").single()["reason"].stringValue(),
        )
    }

    @Test
    fun `二度送ると、二度目の event_ingested は inserted が 0 になる`(output: CapturedOutput) {
        // #5 の本題。received だけを見ていると、再送された重複を実数と読む
        send(DELIVERED).andExpect { status { isOk() } }
        send(DELIVERED).andExpect { status { isOk() } }

        val ingested = entries(output, "event.ingested")
        assertEquals(2, ingested.size)
        assertEquals(1, ingested[0]["received"].intValue())
        assertEquals(1, ingested[0]["inserted"].intValue())
        assertEquals(1, ingested[1]["received"].intValue())
        assertEquals(0, ingested[1]["inserted"].intValue())
    }

    @Test
    fun `内訳は新規に入った分だけを数える`(output: CapturedOutput) {
        send(
            """
            [
              {"sg_event_id":"a","email":"user@example.com","event":"delivered","timestamp":1798723200},
              {"sg_event_id":"b","email":"user@example.com","event":"bounce","timestamp":1798723260}
            ]
            """.trimIndent(),
        ).andExpect { status { isOk() } }

        val byType = entries(output, "event.ingested").single()["by_type"]
        assertEquals(1, byType["delivered"].intValue())
        assertEquals(1, byType["bounce"].intValue())
    }

    @Test
    fun `解釈できないボディでは event_unparseable が出る`(output: CapturedOutput) {
        // 200 で受け切るので、レスポンスからは異常だと分からない
        // （README「200 と 500 の切り分け」）。気づく手段はこの 1 行だけである
        send("""[{"sg_event_id":"a",""").andExpect { status { isOk() } }

        assertEquals(1, entries(output, "event.unparseable").size)
        assertTrue(entries(output, "event.ingested").isEmpty())
    }

    @Test
    fun `解釈できないボディの中身はログに出ない`(output: CapturedOutput) {
        send("""[{"sg_event_id":"a","email":"user@example.com",""").andExpect { status { isOk() } }

        val unparseable = entries(output, "event.unparseable").single().toString()
        assertFalse(unparseable.contains("user@example.com"), unparseable)
    }

    @Test
    fun `ログの各行は severity と message を持つ`(output: CapturedOutput) {
        send(DELIVERED).andExpect { status { isOk() } }

        val received = entries(output, "webhook.received").single()
        assertEquals("INFO", received["severity"].stringValue())
        assertTrue(received["message"].stringValue().isNotEmpty())
        assertNull(received["stack_trace"])
    }

    companion object {
        val KEY_PAIR: KeyPair = TestKeyPair.generate()

        private val DELIVERED =
            """[{"sg_event_id":"a","email":"user@example.com","event":"delivered","timestamp":1798723200}]"""

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            PostgresContainer.register(registry)
            registry.add("sendgrid.webhook.public-key") { TestKeyPair.publicKeyBase64(KEY_PAIR) }
        }
    }
}
