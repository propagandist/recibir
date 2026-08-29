package io.propagandist.recibir.reconcile

import io.propagandist.recibir.support.TEST_API_KEY
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.anything
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Suppression API の読み方を確かめる。**ネットワークにも SendGrid アカウントにも触らない。**
 *
 * `MockRestServiceServer` でスタブする（`spring-test`）。**WireMock を入れていない**
 * ——ここで要るのは「出ていくリクエストの形」と「返ってきた配列の読み方」だけである。
 *
 * 組み立ては [sendGridRestClient] を通す。`Authorization` が付くことは、
 * Bean を組み立てる側ではなく**出ていくリクエスト**で確かめたい。
 */
class SuppressionApiClientTest {
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = SuppressionApiClient(sendGridRestClient(builder, TEST_API_KEY))

    /** `email` だけを持つ応答を作る。他のフィールドは読まないので置かない。 */
    private fun entries(
        count: Int,
        prefix: String = "u",
    ) = (0 until count).joinToString(",", "[", "]") { """{"email":"$prefix$it@example.com"}""" }

    private fun respondWith(
        body: String,
        count: ExpectedCount = ExpectedCount.once(),
    ) = server.expect(count, anything()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON))

    @Test
    fun `Authorization に Bearer が付く`() {
        server
            .expect(anything())
            .andExpect(header("Authorization", "Bearer $TEST_API_KEY"))
            .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON))

        client.fetch(SuppressionSource.BOUNCES)

        server.verify()
    }

    @Test
    fun `叩く先は api_sendgrid_com のパスで、1 ページ目の offset は 0`() {
        server
            .expect(requestTo("https://api.sendgrid.com/v3/suppression/bounces?limit=500&offset=0"))
            .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON))

        client.fetch(SuppressionSource.BOUNCES)

        server.verify()
    }

    @Test
    fun `source ごとに違うパスを叩く`() {
        // パスと source の対応が入れ替わると、別のリストの reason_code が付く
        SuppressionSource.entries.forEach { source ->
            val each = RestClient.builder()
            val stub = MockRestServiceServer.bindTo(each).build()
            stub
                .expect(requestTo("https://api.sendgrid.com${source.path}?limit=500&offset=0"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON))

            SuppressionApiClient(sendGridRestClient(each, TEST_API_KEY)).fetch(source)

            stub.verify()
        }
    }

    @Test
    fun `ログに出す label はパスの末尾と同じ`() {
        assertEquals("spam_reports", SuppressionSource.SPAM_REPORTS.label)
        assertEquals("bounces", SuppressionSource.BOUNCES.label)
        assertEquals("invalid_emails", SuppressionSource.INVALID_EMAILS.label)
        assertEquals("blocks", SuppressionSource.BLOCKS.label)
    }

    @Test
    fun `500 件返ると次のページを取りに行く`() {
        server
            .expect(queryParam("offset", "0"))
            .andRespond(withSuccess(entries(500, "a"), MediaType.APPLICATION_JSON))
        server
            .expect(queryParam("offset", "500"))
            .andRespond(withSuccess(entries(3, "b"), MediaType.APPLICATION_JSON))

        val emails = client.fetch(SuppressionSource.BOUNCES)

        assertEquals(503, emails.size)
        server.verify()
    }

    @Test
    fun `500 件に満たなければそこで終わる`() {
        respondWith(entries(4))

        val emails = client.fetch(SuppressionSource.BOUNCES)

        assertEquals(4, emails.size)
        // 2 ページ目を取りに行っていたら、スタブが尽きて落ちる
        server.verify()
    }

    @Test
    fun `空のリストでも壊れない`() {
        respondWith("[]")

        assertEquals(emptySet(), client.fetch(SuppressionSource.BLOCKS))
    }

    @Test
    fun `同じページが返り続けても上限で止まる`() {
        // これが無いと、SendGrid が同じページを返した日に日次バッチが終わらなくなる
        respondWith(entries(500), ExpectedCount.times(SuppressionApiClient.MAX_PAGES))

        val emails = client.fetch(SuppressionSource.BOUNCES)

        // 毎回同じアドレスなので、集合は 1 ページ分のまま
        assertEquals(500, emails.size)
        server.verify()
    }

    @Test
    fun `spam_reports の応答に reason が無くても読める`() {
        // 4 つのうちここだけ形が違う（reason を持たず ip を持つ。2026-08-29 に原文で確認）
        respondWith("""[{"created":1443651141,"email":"user1@example.com","ip":"10.63.202.100"}]""")

        assertEquals(setOf("user1@example.com"), client.fetch(SuppressionSource.SPAM_REPORTS))
    }

    @Test
    fun `知らないフィールドが増えても読める`() {
        // SendGrid はフィールドを予告なく足す（README「生 JSON を捨てない」）
        respondWith("""[{"email":"user@example.com","added_later":{"nested":true}}]""")

        assertEquals(setOf("user@example.com"), client.fetch(SuppressionSource.BOUNCES))
    }

    @Test
    fun `401 のとき、例外に API キーが出ない`() {
        // 例外はそのまま上がってログに出る。そこへ鍵が混ざると、履歴に残る形で漏れる
        server.expect(anything()).andRespond(withUnauthorizedRequest())

        val thrown = assertFailsWith<RestClientResponseException> { client.fetch(SuppressionSource.BOUNCES) }

        assertFalse(thrown.toString().contains(TEST_API_KEY), thrown.toString())
        assertFalse(thrown.message.orEmpty().contains("Bearer"), thrown.message.orEmpty())
    }
}
