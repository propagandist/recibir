package io.propagandist.recibir.event

import io.propagandist.jooq.Tables.EMAIL_ADDRESS_STATE
import io.propagandist.jooq.Tables.SENDGRID_EVENT
import io.propagandist.recibir.support.PostgresContainer
import io.propagandist.recibir.support.TestKeyPair
import org.jooq.DSLContext
import org.jooq.JSONB
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 起動引数で全件再構築が起きること、**引数が無ければ起きないこと**を確かめる。
 *
 * イベントは窓の外へ置く。スケジューラが拾ってしまうと、
 * 引数を見ずに作り直したのか区別できなくなる（`EventProjectorTest` と同じ理由）。
 *
 * DB は Testcontainers が立てる。**Docker が要る。**
 */
@SpringBootTest
class RebuildAllRunnerTest {
    @Autowired
    private lateinit var runner: RebuildAllRunner

    @Autowired
    private lateinit var dsl: DSLContext

    @BeforeEach
    fun clean() {
        dsl.deleteFrom(SENDGRID_EVENT).execute()
        dsl.deleteFrom(EMAIL_ADDRESS_STATE).execute()
        dsl
            .insertInto(SENDGRID_EVENT)
            .set(SENDGRID_EVENT.SG_EVENT_ID, "rebuild-1")
            .set(SENDGRID_EVENT.EMAIL, EMAIL)
            .set(SENDGRID_EVENT.EVENT, "bounce")
            .set(SENDGRID_EVENT.BOUNCE_TYPE, "bounce")
            .set(SENDGRID_EVENT.OCCURRED_AT, Instant.ofEpochSecond(1798723200).atOffset(ZoneOffset.UTC))
            .set(SENDGRID_EVENT.RECEIVED_AT, OffsetDateTime.now().minusHours(1))
            .set(SENDGRID_EVENT.PAYLOAD, JSONB.valueOf("{}"))
            .execute()
    }

    private fun state() = dsl.fetchOne(EMAIL_ADDRESS_STATE, EMAIL_ADDRESS_STATE.EMAIL.eq(EMAIL))

    @Test
    fun `引数が付いていれば全件を作り直す`() {
        runner.run(DefaultApplicationArguments("--rebuild-all"))

        assertNotNull(state())
    }

    @Test
    fun `引数が無ければ何もしない`() {
        // 起動のたびに全件を作り直すと、規模が大きくなった日に起動が止まる
        runner.run(DefaultApplicationArguments())

        assertNull(state())
    }

    companion object {
        private const val EMAIL = "user@example.com"

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            PostgresContainer.register(registry)
            registry.add("sendgrid.webhook.public-key") { TestKeyPair.publicKeyBase64(TestKeyPair.generate()) }
        }
    }
}
