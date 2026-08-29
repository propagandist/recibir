package io.propagandist.recibir.event

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 状態決定そのものを確かめる。**DB も Spring も要らない。**
 *
 * 分けてあるのは、ここが「対象アドレスの全イベントから決まる」という主張の置き場だからである
 * ——順序の乱れも重複も、入力のリストを並べ替えるだけで書ける。
 * 窓・書き込み・ログは `EventProjectorTest` が見る。
 */
class AddressStateTest {
    private fun event(
        event: String,
        bounceType: String? = null,
        occurredAt: Long = BASE,
    ) = AddressEvent(event, bounceType, Instant.ofEpochSecond(occurredAt).atOffset(ZoneOffset.UTC))

    @Test
    fun `bounce は sendable を落とし、理由は hard_bounce`() {
        val state = AddressState.from(listOf(event("bounce", bounceType = "bounce")))

        assertFalse(state.sendable)
        assertEquals("hard_bounce", state.reasonCode)
    }

    @Test
    fun `spamreport の理由は spam_report`() {
        val state = AddressState.from(listOf(event("spamreport")))

        assertFalse(state.sendable)
        assertEquals("spam_report", state.reasonCode)
    }

    @Test
    fun `unsubscribe の理由は unsubscribe`() {
        // docs/SPEC.md §4.4 の優先度に unsubscribe を足した分（#36）。
        // §3.2 が要求する reason_code を、足す前の手順では作れなかった
        val state = AddressState.from(listOf(event("unsubscribe")))

        assertFalse(state.sendable)
        assertEquals("unsubscribe", state.reasonCode)
    }

    @Test
    fun `delivered の後に processed が届いても壊れない`() {
        // 作業順序 8 の本題。SendGrid のイベントは順序が乱れる
        val inOrder = AddressState.from(listOf(event("processed", occurredAt = BASE), event("delivered", occurredAt = BASE + 60)))
        val reversed = AddressState.from(listOf(event("delivered", occurredAt = BASE + 60), event("processed", occurredAt = BASE)))

        assertTrue(inOrder.sendable)
        assertEquals(inOrder, reversed)
    }

    @Test
    fun `bounce の後に delivered が届いても sendable は戻らない`() {
        // 単発のイベントを順に適用していたら、ここで true に戻る
        val state =
            AddressState.from(
                listOf(
                    event("bounce", bounceType = "bounce", occurredAt = BASE),
                    event("delivered", occurredAt = BASE + 60),
                ),
            )

        assertFalse(state.sendable)
        assertEquals("hard_bounce", state.reasonCode)
    }

    @Test
    fun `同じイベントを二度読んでも状態は変わらない`() {
        // 窓が重なる以上、同じイベントは何度も読まれる（docs/SPEC.md §4.4）
        val once = AddressState.from(listOf(event("spamreport")))
        val twice = AddressState.from(listOf(event("spamreport"), event("spamreport")))

        assertEquals(once, twice)
    }

    @Test
    fun `deferred は sendable を落とさず、soft_bounce_count を増やす`() {
        // 一時的な失敗である。恒久的な不達と混ぜない
        val state = AddressState.from(listOf(event("deferred", occurredAt = BASE), event("deferred", occurredAt = BASE + 60)))

        assertTrue(state.sendable)
        assertNull(state.reasonCode)
        assertEquals(2, state.softBounceCount)
        assertEquals(Instant.ofEpochSecond(BASE + 60), state.lastFailureAt?.toInstant())
    }

    @Test
    fun `blocked は sendable を落とさず、soft_bounce_count を増やす`() {
        // event は bounce だが type が blocked。受け取り側が一時的に拒んだだけである
        val state = AddressState.from(listOf(event("bounce", bounceType = "blocked")))

        assertTrue(state.sendable)
        assertNull(state.reasonCode)
        assertEquals(1, state.softBounceCount)
    }

    @Test
    fun `type が無い bounce は恒久扱いにする`() {
        // 分類できないものを一時扱いにすると、届かないアドレスへ送り続ける
        val state = AddressState.from(listOf(event("bounce", bounceType = null)))

        assertFalse(state.sendable)
        assertEquals("hard_bounce", state.reasonCode)
        assertEquals(0, state.softBounceCount)
    }

    @Test
    fun `dropped だけなら reason_code は付かない`() {
        // 送れないことは分かるが、理由は他のイベントが持つ。
        // reason の文字列を読んで振り分けない（SendGrid が文言を変えた日に壊れる）
        val state = AddressState.from(listOf(event("dropped", occurredAt = BASE)))

        assertFalse(state.sendable)
        assertNull(state.reasonCode)
        assertEquals(Instant.ofEpochSecond(BASE), state.lastFailureAt?.toInstant())
    }

    @Test
    fun `group_unsubscribe と group_resubscribe は状態を変えない`() {
        // グループ単位の購読可否は SendGrid 側が持つ。粒度が違う（AddressState.from の KDoc）
        val state =
            AddressState.from(
                listOf(
                    event("delivered"),
                    event("group_unsubscribe"),
                    event("group_resubscribe"),
                ),
            )

        assertTrue(state.sendable)
        assertNull(state.reasonCode)
        assertNull(state.lastFailureAt)
    }

    @Test
    fun `open と click は状態を変えない`() {
        val state = AddressState.from(listOf(event("delivered"), event("open"), event("click")))

        assertEquals(AddressState.from(listOf(event("delivered"))), state)
    }

    @Test
    fun `account_status_change は状態を変えない`() {
        // 送信者アカウントの状態であって、宛先アドレスの話ではない
        val state = AddressState.from(listOf(event("delivered"), event("account_status_change")))

        assertTrue(state.sendable)
        assertNull(state.reasonCode)
    }

    @Test
    fun `優先度は spamreport が最も強い`() {
        val state =
            AddressState.from(
                listOf(
                    event("dropped"),
                    event("unsubscribe"),
                    event("bounce", bounceType = "bounce"),
                    event("spamreport"),
                ).shuffled(),
            )

        assertEquals("spam_report", state.reasonCode)
    }

    @Test
    fun `優先度は hard_bounce が unsubscribe より強い`() {
        val state =
            AddressState.from(
                listOf(event("unsubscribe"), event("bounce", bounceType = "bounce"), event("dropped")).shuffled(),
            )

        assertEquals("hard_bounce", state.reasonCode)
    }

    @Test
    fun `優先度は unsubscribe が dropped より強い`() {
        // dropped は「抑制リストに載っていたので捨てた」という結果で、unsubscribe はその原因である
        val state = AddressState.from(listOf(event("dropped"), event("unsubscribe")).shuffled())

        assertEquals("unsubscribe", state.reasonCode)
    }

    @Test
    fun `unsubscribe では last_failure_at が動かない`() {
        // 購読解除は失敗ではない。列名が意味するものを守る
        val state = AddressState.from(listOf(event("unsubscribe")))

        assertNull(state.lastFailureAt)
    }

    @Test
    fun `last_failure_at は失敗イベントの最新を採る`() {
        val state =
            AddressState.from(
                listOf(
                    event("bounce", bounceType = "bounce", occurredAt = BASE),
                    event("deferred", occurredAt = BASE + 600),
                    // 失敗ではないので、これが最新でも last_failure_at にはならない
                    event("delivered", occurredAt = BASE + 1200),
                ),
            )

        assertEquals(Instant.ofEpochSecond(BASE + 600), state.lastFailureAt?.toInstant())
    }

    @Test
    fun `イベントが 1 件も無ければ送ってよい`() {
        val state = AddressState.from(emptyList())

        assertTrue(state.sendable)
        assertNull(state.reasonCode)
        assertNull(state.lastFailureAt)
        assertEquals(0, state.softBounceCount)
    }

    private companion object {
        const val BASE = 1798723200L
    }
}
