package io.propagandist.recibir.webhook

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.JsonTest
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `SendGridEvent` が、SendGrid の送ってくる形の JSON から読めることを確かめる。
 *
 * **マッパーを手で組まない。** Boot が組み立てたものを注入して、
 * **本番と同じ設定で読めること**を見る（`CLAUDE.md`「Spring Boot 4 の注意点」）。
 *
 * この型で受信ボディを受け取るわけではない（署名検証には生バイト列が要る）。
 * ここで固定しているのは、**投入側が使う射影の形**である。
 */
@JsonTest
class SendGridEventTest {
    @Autowired
    private lateinit var jsonMapper: JsonMapper

    @Test
    fun `キー名が Kotlin の命名と違うフィールドを拾う`() {
        val json =
            """
            {
              "sg_event_id": "ZGVsaXZlcmVk",
              "sg_message_id": "abc123.filterdrecv-aaa-bbb.1",
              "smtp-id": "<14c5d75ce93.dfd.64b469@ismtpd-555>",
              "email": "user@example.com",
              "event": "bounce",
              "type": "blocked",
              "reason": "550 5.1.1 The email account does not exist",
              "status": "5.1.1",
              "job_id": "job-42",
              "timestamp": 1798723200
            }
            """.trimIndent()

        val event = jsonMapper.readValue(json, SendGridEvent::class.java)

        assertEquals("ZGVsaXZlcmVk", event.sgEventId)
        // 接尾辞を切らずにそのまま持つ（docs/SPEC.md §3.1）
        assertEquals("abc123.filterdrecv-aaa-bbb.1", event.sgMessageId)
        // ハイフンを含むキー。素直に書くと拾えない
        assertEquals("<14c5d75ce93.dfd.64b469@ismtpd-555>", event.smtpId)
        // 受信 JSON では type。カラム名は bounce_type
        assertEquals("blocked", event.bounceType)
        assertEquals("job-42", event.jobId)
    }

    @Test
    fun `知らないフィールドが増えても落ちない`() {
        // SendGrid は予告なくフィールドを足す（README「生 JSON を捨てない」）。
        // 落ちると、その日から受信が全部止まる
        val json =
            """
            {
              "sg_event_id": "abc",
              "email": "user@example.com",
              "event": "delivered",
              "timestamp": 1798723200,
              "category": ["cat facts"],
              "unknown_field_added_later": {"nested": true}
            }
            """.trimIndent()

        val event = jsonMapper.readValue(json, SendGridEvent::class.java)

        assertEquals("delivered", event.event)
        assertNull(event.reason)
    }

    @Test
    fun `配列としてまとめて読める`() {
        val json =
            """
            [
              {"sg_event_id": "a", "email": "user@example.com", "event": "processed", "timestamp": 1798723200},
              {"sg_event_id": "b", "email": "user@example.com", "event": "delivered", "timestamp": 1798723260}
            ]
            """.trimIndent()

        val events = jsonMapper.readValue(json, Array<SendGridEvent>::class.java)

        assertEquals(listOf("a", "b"), events.map { it.sgEventId })
    }

    @Test
    fun `Instant の範囲を超える timestamp でも読める`() {
        // Long のまま持っている効果がここに出る。Instant にしていると、
        // 変換で DateTimeException が出て受信経路の中で落ちる（#29 と同じ形）
        val json =
            """
            {"sg_event_id": "a", "email": "user@example.com", "event": "delivered", "timestamp": 999999999999999999}
            """.trimIndent()

        val event = jsonMapper.readValue(json, SendGridEvent::class.java)

        assertEquals(999999999999999999L, event.timestamp)
    }
}
