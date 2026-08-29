package io.propagandist.recibir.event

import io.propagandist.jooq.Tables.EMAIL_ADDRESS_STATE
import io.propagandist.jooq.Tables.SENDGRID_EVENT
import io.propagandist.recibir.support.PostgresContainer
import io.propagandist.recibir.support.registerSendGridProperties
import io.propagandist.recibir.support.structuredLogs
import org.jooq.DSLContext
import org.jooq.JSONB
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 窓・書き込み・ログを、実際の DB で確かめる。
 *
 * 状態決定そのものは `AddressStateTest` が見る（あちらは DB が要らない）。
 * ここが見るのは**どれを・いつ作り直すか**と、**送れなくなったことに気づけるか**である。
 *
 * **既定では窓の外へ置く。** コンテキストにはスケジューラが居て（`config/SchedulingConfig`）、
 * テストの最中にも窓を拾いに来る。窓の外に置いてあれば割り込まれても結果が変わらない
 * ——窓を見るテストだけが窓の中を使い、**そちらは何度作り直しても同じ状態になる。**
 *
 * DB は Testcontainers が立てる。**Docker が要る。**
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension::class)
class EventProjectorTest {
    @Autowired
    private lateinit var projector: EventProjector

    @Autowired
    private lateinit var dsl: DSLContext

    private var seq = 0

    @BeforeEach
    fun clean() {
        dsl.deleteFrom(SENDGRID_EVENT).execute()
        dsl.deleteFrom(EMAIL_ADDRESS_STATE).execute()
    }

    /**
     * イベントを 1 件、直接入れる。
     *
     * **投入サービスを通していない。** `received_at` を明示したいためで、あちらは
     * DB の `DEFAULT now()` に任せる（`docs/SPEC.md` §3.1）。窓の内と外を書き分けられない。
     */
    private fun insert(
        email: String,
        event: String,
        bounceType: String? = null,
        receivedAt: OffsetDateTime = OUTSIDE_WINDOW,
        occurredAt: Long = BASE,
    ) {
        dsl
            .insertInto(SENDGRID_EVENT)
            .set(SENDGRID_EVENT.SG_EVENT_ID, "e${seq++}")
            .set(SENDGRID_EVENT.EMAIL, email)
            .set(SENDGRID_EVENT.EVENT, event)
            .set(SENDGRID_EVENT.BOUNCE_TYPE, bounceType)
            .set(SENDGRID_EVENT.OCCURRED_AT, Instant.ofEpochSecond(occurredAt).atOffset(ZoneOffset.UTC))
            .set(SENDGRID_EVENT.RECEIVED_AT, receivedAt)
            .set(SENDGRID_EVENT.PAYLOAD, JSONB.valueOf("{}"))
            .execute()
    }

    private fun state(email: String) = dsl.fetchOne(EMAIL_ADDRESS_STATE, EMAIL_ADDRESS_STATE.EMAIL.eq(email))

    /** `updated_at` を除いた中身。作り直しのたびに動きうる列なので、同一性の比較からは外す。 */
    private fun snapshot(email: String) =
        state(email)!!.let {
            listOf(it.sendable, it.reasonCode, it.lastFailureAt?.toInstant(), it.softBounceCount)
        }

    @Test
    fun `窓に入ったアドレスだけが作り直される`() {
        insert("inside@example.com", "delivered", receivedAt = INSIDE_WINDOW)
        insert("outside@example.com", "delivered", receivedAt = OUTSIDE_WINDOW)

        projector.projectRecent()

        assertNotNull(state("inside@example.com"))
        assertNull(state("outside@example.com"))
    }

    @Test
    fun `窓の外のイベントも、同じアドレスの状態には含まれる`() {
        // 窓が決めるのは「どのアドレスを作り直すか」だけで、
        // 状態はそのアドレスの全イベントから決まる（docs/SPEC.md §4.4）
        insert("user@example.com", "bounce", bounceType = "bounce", receivedAt = OUTSIDE_WINDOW, occurredAt = BASE)
        insert("user@example.com", "delivered", receivedAt = INSIDE_WINDOW, occurredAt = BASE + 60)

        projector.projectRecent()

        val row = state("user@example.com")!!
        assertFalse(row.sendable)
        assertEquals("hard_bounce", row.reasonCode)
    }

    @Test
    fun `遅れて届いたイベントも窓に入る`() {
        // received_at で絞るのはこのため。occurred_at で絞ると、
        // SendGrid 側の時刻が古いイベントは届いた日にもう窓から外れている
        insert("user@example.com", "bounce", bounceType = "bounce", receivedAt = INSIDE_WINDOW, occurredAt = BASE - 86400)

        projector.projectRecent()

        assertFalse(state("user@example.com")!!.sendable)
    }

    @Test
    fun `変わっていなければ updated_at は動かない`() {
        insert("user@example.com", "delivered")
        projector.project("user@example.com")
        val first = state("user@example.com")!!.updatedAt

        projector.project("user@example.com")

        // 窓が重なる以上、同じアドレスは何度も通る。毎回書くと
        // 「いつ状態が変わったか」が読めなくなる
        assertEquals(first.toInstant(), state("user@example.com")!!.updatedAt.toInstant())
    }

    @Test
    fun `新しいイベントが増えれば書き直される`() {
        insert("user@example.com", "delivered")
        projector.project("user@example.com")

        insert("user@example.com", "spamreport")
        projector.project("user@example.com")

        val row = state("user@example.com")!!
        assertFalse(row.sendable)
        assertEquals("spam_report", row.reasonCode)
    }

    @Test
    fun `email_address_state を全削除してから rebuildAll すると同じ状態に戻る`() {
        // 二次テーブルは捨てて作り直せる（docs/er.md「正本は sendgrid_event」）
        insert("hard@example.com", "bounce", bounceType = "bounce")
        insert("soft@example.com", "deferred", occurredAt = BASE)
        insert("soft@example.com", "delivered", occurredAt = BASE + 60)
        insert("gone@example.com", "unsubscribe")
        val addresses = listOf("hard@example.com", "soft@example.com", "gone@example.com")
        projector.rebuildAll()
        val before = addresses.map { snapshot(it) }

        dsl.deleteFrom(EMAIL_ADDRESS_STATE).execute()
        projector.rebuildAll()

        assertEquals(before, addresses.map { snapshot(it) })
    }

    @Test
    fun `rebuildAll は窓の外のアドレスも作り直す`() {
        insert("old@example.com", "bounce", bounceType = "bounce", receivedAt = OUTSIDE_WINDOW)

        projector.rebuildAll()

        assertFalse(state("old@example.com")!!.sendable)
    }

    @Test
    fun `イベントが 1 件も無い状態行は rebuildAll で触られない`() {
        // SuppressionReconciler が Suppression API を正として先に立てた行
        // （docs/er.md の多重度 3 行目）。ここが消すと、取りこぼしの跡が毎回消える
        dsl
            .insertInto(EMAIL_ADDRESS_STATE)
            .set(EMAIL_ADDRESS_STATE.EMAIL, "invalid@example.com")
            .set(EMAIL_ADDRESS_STATE.SENDABLE, false)
            .set(EMAIL_ADDRESS_STATE.REASON_CODE, "invalid")
            .set(EMAIL_ADDRESS_STATE.UPDATED_AT, OffsetDateTime.now())
            .execute()
        insert("user@example.com", "delivered")

        projector.rebuildAll()

        assertEquals("invalid", state("invalid@example.com")!!.reasonCode)
    }

    @Test
    fun `送れなくなると address_unsendable がマスク済みで出る`(output: CapturedOutput) {
        insert("user@example.com", "bounce", bounceType = "bounce")

        projector.project("user@example.com")

        val entry = structuredLogs(output, "address.unsendable").single()
        assertEquals("***@example.com", entry["email"].stringValue())
        assertEquals("hard_bounce", entry["reason_code"].stringValue())
        assertEquals("WARNING", entry["severity"].stringValue())
        assertFalse(entry.toString().contains("user@"), entry.toString())
    }

    @Test
    fun `変わっていなければ address_unsendable は出ない`(output: CapturedOutput) {
        // 窓は重なる。毎分同じ行が出ると、監視の側で数を読めなくなる
        insert("user@example.com", "spamreport")

        projector.project("user@example.com")
        projector.project("user@example.com")

        assertEquals(1, structuredLogs(output, "address.unsendable").size)
    }

    @Test
    fun `送れるアドレスでは address_unsendable が出ない`(output: CapturedOutput) {
        insert("user@example.com", "deferred")

        projector.project("user@example.com")

        assertTrue(structuredLogs(output, "address.unsendable").isEmpty())
        // 一時的な失敗なので、状態は残るが送れるままである
        assertTrue(state("user@example.com")!!.sendable)
    }

    @Test
    fun `dropped だけのアドレスでも address_unsendable は出る`(output: CapturedOutput) {
        // reason_code は付かないが、送れなくなったことは伝える必要がある
        insert("user@example.com", "dropped")

        projector.project("user@example.com")

        val entry = structuredLogs(output, "address.unsendable").single()
        assertTrue(entry["reason_code"].isNull, entry.toString())
    }

    companion object {
        private const val BASE = 1798723200L

        /** 窓（10 分）の中。**スケジューラが同じことをしても結果は変わらない。** */
        private val INSIDE_WINDOW: OffsetDateTime = OffsetDateTime.now()

        /** 窓の外。ここに置いたものは、テストが自分で呼ぶまで作り直されない。 */
        private val OUTSIDE_WINDOW: OffsetDateTime = OffsetDateTime.now().minusHours(1)

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            PostgresContainer.register(registry)
            registerSendGridProperties(registry)
        }
    }
}
