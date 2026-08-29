package io.propagandist.recibir.reconcile

import io.propagandist.jooq.Tables.EMAIL_ADDRESS_STATE
import io.propagandist.jooq.Tables.SENDGRID_EVENT
import io.propagandist.recibir.support.PostgresContainer
import io.propagandist.recibir.support.TEST_API_KEY
import io.propagandist.recibir.support.registerSendGridProperties
import io.propagandist.recibir.support.structuredLogs
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 突き合わせの結果——**何を書き、何を数え、何に触らないか**——を確かめる。
 *
 * SendGrid は `MockRestServiceServer` でスタブし、DB は Testcontainers が立てる。
 * **SendGrid アカウントもネットワークも要らない**（`docs/SPEC.md` §6）。**Docker は要る。**
 *
 * 突き合わせは自分で組み立てる。Bean のほうは本物の HTTP クライアントを持つが、
 * 日次 03:00 UTC まで動かないので、テストの最中には出ていかない。
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension::class)
class SuppressionReconcilerTest {
    @Autowired
    private lateinit var dsl: DSLContext

    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build()
    private val reconciler by lazy {
        SuppressionReconciler(SuppressionApiClient(sendGridRestClient(builder, TEST_API_KEY)), dsl)
    }

    @BeforeEach
    fun clean() {
        // **sendgrid_event も空にする。** 射影のスケジューラが同じ DB を見ているので、
        // 残っていると、突き合わせが書いたのか射影が書いたのか読めなくなる
        dsl.deleteFrom(SENDGRID_EVENT).execute()
        dsl.deleteFrom(EMAIL_ADDRESS_STATE).execute()
    }

    /**
     * 4 つのリストに何が載っているかを決める。**指定しなかったものは空で返る。**
     *
     * 4 つとも叩かれるので、1 つでもスタブが欠けるとそこで落ちる。
     */
    private fun stub(vararg lists: Pair<SuppressionSource, List<String>>) {
        val given = lists.toMap()
        SuppressionSource.entries.forEach { source ->
            val body = given[source].orEmpty().joinToString(",", "[", "]") { """{"email":"$it"}""" }
            server
                .expect(requestTo("https://api.sendgrid.com${source.path}?limit=500&offset=0"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON))
        }
    }

    private fun givenState(
        email: String,
        sendable: Boolean,
        reasonCode: String? = null,
        softBounceCount: Int = 0,
        lastFailureAt: OffsetDateTime? = null,
    ) {
        dsl
            .insertInto(EMAIL_ADDRESS_STATE)
            .set(EMAIL_ADDRESS_STATE.EMAIL, email)
            .set(EMAIL_ADDRESS_STATE.SENDABLE, sendable)
            .set(EMAIL_ADDRESS_STATE.REASON_CODE, reasonCode)
            .set(EMAIL_ADDRESS_STATE.SOFT_BOUNCE_COUNT, softBounceCount)
            .set(EMAIL_ADDRESS_STATE.LAST_FAILURE_AT, lastFailureAt)
            .set(EMAIL_ADDRESS_STATE.UPDATED_AT, OffsetDateTime.now())
            .execute()
    }

    private fun state(email: String) = dsl.fetchOne(EMAIL_ADDRESS_STATE, EMAIL_ADDRESS_STATE.EMAIL.eq(email))

    @Test
    fun `4 つのリストを全部叩く`() {
        stub()

        reconciler.reconcile()

        server.verify()
    }

    @Test
    fun `bounces にあって行が無いアドレスへ hard_bounce が書かれる`() {
        // Webhook を取りこぼしたアドレスの本体。ここが埋まらないと、送り続けることになる
        stub(SuppressionSource.BOUNCES to listOf("gone@example.com"))

        reconciler.reconcile()

        val row = state("gone@example.com")!!
        assertFalse(row.sendable)
        assertEquals("hard_bounce", row.reasonCode)
    }

    @Test
    fun `invalid_emails 由来の reason_code は invalid`() {
        // invalid を付けられるのはここだけである（docs/SPEC.md §3.2 の 4 つ目）
        stub(SuppressionSource.INVALID_EMAILS to listOf("nodomain@example.invalid"))

        reconciler.reconcile()

        assertEquals("invalid", state("nodomain@example.invalid")!!.reasonCode)
    }

    @Test
    fun `spam_reports 由来の reason_code は spam_report`() {
        stub(SuppressionSource.SPAM_REPORTS to listOf("angry@example.com"))

        reconciler.reconcile()

        assertEquals("spam_report", state("angry@example.com")!!.reasonCode)
    }

    @Test
    fun `blocks では状態が書き換わらない`() {
        // 一時的な失敗である。落とすと、SendGrid 側が外した日に戻す手段が無い
        stub(SuppressionSource.BLOCKS to listOf("blocked@example.com"))

        reconciler.reconcile()

        assertNull(state("blocked@example.com"))
    }

    @Test
    fun `blocks の差分は、行が無いものだけを数える`(output: CapturedOutput) {
        // 行があって sendable = true なのは正常な状態である（blocked は sendable を落とさない）
        givenState("known@example.com", sendable = true)
        stub(SuppressionSource.BLOCKS to listOf("known@example.com", "unknown@example.com"))

        reconciler.reconcile()

        val drift = structuredLogs(output, "reconcile.drift").single()
        assertEquals("blocks", drift["source"].stringValue())
        assertEquals(1, drift["count"].intValue())
    }

    @Test
    fun `SendGrid 側に無いアドレスの sendable は true へ戻らない`() {
        // 抑制リストは運用者が手で外すことがある。戻すと、その瞬間に過去のバウンス先へ送り始める
        givenState("bounced@example.com", sendable = false, reasonCode = "hard_bounce")
        stub()

        reconciler.reconcile()

        val row = state("bounced@example.com")!!
        assertFalse(row.sendable)
        assertEquals("hard_bounce", row.reasonCode)
    }

    @Test
    fun `既に送れない印が付いていれば差分に数えない`(output: CapturedOutput) {
        givenState("known@example.com", sendable = false, reasonCode = "hard_bounce")
        stub(SuppressionSource.BOUNCES to listOf("known@example.com"))

        reconciler.reconcile()

        assertTrue(structuredLogs(output, "reconcile.drift").isEmpty())
    }

    @Test
    fun `2 つのリストに載っていたら、優先度の高いほうの reason_code が残る`() {
        // 並び順が優先度である（SuppressionSource の KDoc）。spamreport が bounce より強い
        stub(
            SuppressionSource.SPAM_REPORTS to listOf("both@example.com"),
            SuppressionSource.BOUNCES to listOf("both@example.com"),
        )

        reconciler.reconcile()

        assertEquals("spam_report", state("both@example.com")!!.reasonCode)
    }

    @Test
    fun `last_failure_at と soft_bounce_count には触らない`() {
        // どちらも Webhook 由来の値である。Suppression API の created は
        // 「リストに載った時刻」で、意味が違う（SuppressionApiClient の KDoc）
        // マイクロ秒へ丸める。**timestamptz はそこまでしか持たない**ので、
        // ナノ秒のまま入れると、読み戻した値が入れた値と一致しない（2026-08-29 実測）
        val failedAt = OffsetDateTime.now().minusDays(3).truncatedTo(ChronoUnit.MICROS)
        givenState("soft@example.com", sendable = true, softBounceCount = 5, lastFailureAt = failedAt)
        stub(SuppressionSource.BOUNCES to listOf("soft@example.com"))

        reconciler.reconcile()

        val row = state("soft@example.com")!!
        assertFalse(row.sendable)
        assertEquals(5, row.softBounceCount)
        assertEquals(failedAt.toInstant(), row.lastFailureAt?.toInstant())
    }

    @Test
    fun `差分が 0 なら reconcile_drift は出ない`(output: CapturedOutput) {
        // 毎日 WARNING が出続けると、監視の側に無視する習慣ができる
        stub()

        reconciler.reconcile()

        assertTrue(structuredLogs(output, "reconcile.drift").isEmpty())
    }

    @Test
    fun `reconcile_drift は WARNING で、件数と source を持つ`(output: CapturedOutput) {
        stub(SuppressionSource.BOUNCES to listOf("a@example.com", "b@example.com"))

        reconciler.reconcile()

        val drift = structuredLogs(output, "reconcile.drift").single()
        assertEquals("WARNING", drift["severity"].stringValue())
        assertEquals("bounces", drift["source"].stringValue())
        assertEquals(2, drift["count"].intValue())
    }

    @Test
    fun `reconcile_drift にメールアドレスが載らない`(output: CapturedOutput) {
        // 件数で足りる。どれかを知るには DB を引く（docs/SPEC.md §4.7）
        stub(SuppressionSource.BOUNCES to listOf("secret@example.com"))

        reconciler.reconcile()

        val drift = structuredLogs(output, "reconcile.drift").single().toString()
        assertFalse(drift.contains("secret"), drift)
        assertFalse(drift.contains("example.com"), drift)
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
