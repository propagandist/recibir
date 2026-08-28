package io.propagandist.recibir.webhook

import io.propagandist.jooq.Tables.SENDGRID_EVENT
import io.propagandist.recibir.support.PostgresContainer
import io.propagandist.recibir.support.TestKeyPair
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.security.KeyPair
import java.time.Instant
import kotlin.test.assertEquals

/**
 * 受信から DB までを、実物だけで通す。
 *
 * `SendGridWebhookControllerTest` は応答だけを見て投入を差し替えている。
 * **ここは差し替えない**——署名検証・投入・Security のチェーンが、
 * 実際に繋がっていることを確かめるためである。
 *
 * DB は Testcontainers が立てる。**Docker が要る。**
 */
@SpringBootTest
@AutoConfigureMockMvc
class SendGridWebhookIngestTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dsl: DSLContext

    @BeforeEach
    fun clean() {
        dsl.deleteFrom(SENDGRID_EVENT).execute()
    }

    private fun send(payload: String) =
        Instant.now().epochSecond.toString().let { stamp ->
            mockMvc.post(SendGridWebhookController.PATH) {
                header(SendGridWebhookController.SIGNATURE_HEADER, TestKeyPair.sign(KEY_PAIR, stamp, payload.toByteArray()))
                header(SendGridWebhookController.TIMESTAMP_HEADER, stamp)
                contentType = MediaType.APPLICATION_JSON
                content = payload.toByteArray()
            }
        }

    @Test
    fun `署名を通したリクエストのイベントが DB に入る`() {
        send(
            """
            [
              {"sg_event_id":"a","email":"user@example.com","event":"processed","timestamp":1798723200},
              {"sg_event_id":"b","email":"user@example.com","event":"delivered","timestamp":1798723260}
            ]
            """.trimIndent(),
        ).andExpect { status { isOk() } }

        assertEquals(2, dsl.fetchCount(SENDGRID_EVENT))
    }

    @Test
    fun `同じリクエストを二度送っても行は増えない`() {
        // SendGrid の再送。受信口ごと通しても冪等であること
        val payload = """[{"sg_event_id":"dup","email":"user@example.com","event":"delivered","timestamp":1798723200}]"""

        send(payload).andExpect { status { isOk() } }
        send(payload).andExpect { status { isOk() } }

        assertEquals(1, dsl.fetchCount(SENDGRID_EVENT))
    }

    @Test
    fun `署名が合わなければ 403 で、何も入らない`() {
        val payload = """[{"sg_event_id":"a","email":"user@example.com","event":"delivered","timestamp":1798723200}]"""
        val stamp = Instant.now().epochSecond.toString()

        mockMvc
            .post(SendGridWebhookController.PATH) {
                header(SendGridWebhookController.SIGNATURE_HEADER, TestKeyPair.sign(KEY_PAIR, stamp, payload.toByteArray()))
                header(SendGridWebhookController.TIMESTAMP_HEADER, stamp)
                contentType = MediaType.APPLICATION_JSON
                content = "${payload}X".toByteArray()
            }.andExpect { status { isForbidden() } }

        assertEquals(0, dsl.fetchCount(SENDGRID_EVENT))
    }

    @Test
    fun `解釈できないペイロードは 200 で受け切り、何も入らない`() {
        // 500 を返すと SendGrid が再送する。再送されても直らない入力である
        // （README「200 と 500 の切り分け」）
        send("""[{"sg_event_id":"a",""").andExpect { status { isOk() } }

        assertEquals(0, dsl.fetchCount(SENDGRID_EVENT))
    }

    companion object {
        val KEY_PAIR: KeyPair = TestKeyPair.generate()

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            PostgresContainer.register(registry)
            registry.add("sendgrid.webhook.public-key") { TestKeyPair.publicKeyBase64(KEY_PAIR) }
        }
    }
}
