package io.propagandist.recibir.webhook

import io.propagandist.jooq.Tables.SENDGRID_EVENT
import io.propagandist.recibir.support.PostgresContainer
import io.propagandist.recibir.support.registerSendGridProperties
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 投入の冪等性と、解釈できない入力の扱いを確かめる。
 *
 * **トランザクションでロールバックしない。** 各テストの前に消して、**本当に入ったか**を見る
 * ——ロールバック前提にすると、コミットされていないものを「入った」と読んでしまう。
 *
 * DB は Testcontainers が立てる（`support/PostgresContainer`）。**Docker が要る。**
 */
@SpringBootTest
class SendGridEventIngestServiceTest {
    @Autowired
    private lateinit var service: SendGridEventIngestService

    @Autowired
    private lateinit var dsl: DSLContext

    @BeforeEach
    fun clean() {
        dsl.deleteFrom(SENDGRID_EVENT).execute()
    }

    private fun count() = dsl.fetchCount(SENDGRID_EVENT)

    @Test
    fun `配列の要素数だけ行が入る`() {
        service.ingest(
            """
            [
              {"sg_event_id":"a","email":"user@example.com","event":"processed","timestamp":1798723200},
              {"sg_event_id":"b","email":"user@example.com","event":"delivered","timestamp":1798723260}
            ]
            """.trimIndent().toByteArray(),
        )

        assertEquals(2, count())
    }

    @Test
    fun `同じ sg_event_id を二度投入しても 1 行のまま`() {
        // 作業順序 7 の本題。SendGrid は同じイベントを再送する（docs/SPEC.md §3.1）
        val body = """[{"sg_event_id":"dup","email":"user@example.com","event":"delivered","timestamp":1798723200}]"""

        service.ingest(body.toByteArray())
        service.ingest(body.toByteArray())

        assertEquals(1, count())
    }

    @Test
    fun `1 リクエストの中に重複があっても 1 行になる`() {
        val body =
            """
            [
              {"sg_event_id":"same","email":"user@example.com","event":"processed","timestamp":1798723200},
              {"sg_event_id":"same","email":"user@example.com","event":"delivered","timestamp":1798723260}
            ]
            """.trimIndent()

        service.ingest(body.toByteArray())

        assertEquals(1, count())
    }

    @Test
    fun `payload に射影で捨てたキーが残る`() {
        // SendGridEvent は category を持たない。DTO を書き戻していたら、ここで消える
        service.ingest(
            """
            [{"sg_event_id":"a","email":"user@example.com","event":"delivered","timestamp":1798723200,
              "category":["cat facts"],"unknown_field_added_later":{"nested":true}}]
            """.trimIndent().toByteArray(),
        )

        val payload =
            dsl
                .select(SENDGRID_EVENT.PAYLOAD)
                .from(SENDGRID_EVENT)
                .fetchOne()!!
                .value1()
                .data()
        assertTrue(payload.contains("\"category\""), payload)
        assertTrue(payload.contains("\"unknown_field_added_later\""), payload)
    }

    @Test
    fun `occurred_at は SendGrid の timestamp から作られ、received_at とは別`() {
        service.ingest(
            """[{"sg_event_id":"a","email":"user@example.com","event":"delivered","timestamp":1798723200}]"""
                .toByteArray(),
        )

        val row = dsl.selectFrom(SENDGRID_EVENT).fetchOne()!!
        // 瞬間で比べる。timestamptz が保持するのは瞬間で、返るときのオフセットは
        // セッションのタイムゾーンに従う——UTC で入れても +09:00 で返ってくる
        // （2026-08-28 実測）。OffsetDateTime 同士の equals はそこで落ちる
        assertEquals(Instant.ofEpochSecond(1798723200), row.occurredAt.toInstant())
        // received_at は DB の DEFAULT now()。SendGrid 側の時刻とはずれる（docs/SPEC.md §3.1）
        assertTrue(row.receivedAt.toInstant() != row.occurredAt.toInstant())
    }

    @Test
    fun `job_id が無くても入る`() {
        // 外部キーを張っていない理由のひとつ（docs/er.md）。遡って付けられない値である
        service.ingest(
            """[{"sg_event_id":"a","email":"user@example.com","event":"delivered","timestamp":1798723200}]"""
                .toByteArray(),
        )

        assertNull(dsl.selectFrom(SENDGRID_EVENT).fetchOne()!!.jobId)
    }

    // ここから 4 本は、素朴に書くと 500 になるところである。
    // 再送されても直らない入力に 500 を返すと、SendGrid の再送だけが積み上がる。

    @Test
    fun `不正な JSON では何も入らず、例外も出ない`() {
        service.ingest("""[{"sg_event_id":"a",""".toByteArray())

        assertEquals(0, count())
    }

    @Test
    fun `配列でない JSON では何も入らない`() {
        service.ingest(
            """{"sg_event_id":"a","email":"user@example.com","event":"delivered","timestamp":1798723200}"""
                .toByteArray(),
        )

        assertEquals(0, count())
    }

    @Test
    fun `sg_event_id が欠けていると、その配列は丸ごと入らない`() {
        // 部分成功にしない。再送時に何が入っていて何が入っていないかが読めなくなる
        service.ingest(
            """
            [
              {"sg_event_id":"a","email":"user@example.com","event":"processed","timestamp":1798723200},
              {"email":"user@example.com","event":"delivered","timestamp":1798723260}
            ]
            """.trimIndent().toByteArray(),
        )

        assertEquals(0, count())
    }

    @Test
    fun `Instant の範囲を超える timestamp でも落ちない`() {
        // #29 と同じ形。DTO が Long のまま持っているので、落ちるならこの層である
        service.ingest(
            """[{"sg_event_id":"a","email":"user@example.com","event":"delivered","timestamp":9223372036854775807}]"""
                .toByteArray(),
        )

        assertEquals(0, count())
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            PostgresContainer.register(registry)
            registerSendGridProperties(registry)
        }
    }
}
