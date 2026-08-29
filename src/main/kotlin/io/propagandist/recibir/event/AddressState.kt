package io.propagandist.recibir.event

import java.time.OffsetDateTime

/**
 * 射影の入力になるイベント 1 件。
 *
 * `sendgrid_event` から読むのはこの 3 つの列だけである。**`payload` は開かない**——
 * 状態を決めるのに要るものは V1 の時点で列に出してあり、生 JSON を読み直すと、
 * 同じ判断が列とパースの 2 か所に散る。
 *
 * **[io.propagandist.recibir.webhook.SendGridEvent] を使い回していない。**
 * あちらは受信 JSON の射影で、こちらは DB から読む側である。同じ型にすると、
 * 投入側がフィールドを足した日に、射影の入力が黙って変わる。
 *
 * @property event `sendgrid_event.event`。delivered / bounce / dropped など
 * @property bounceType `sendgrid_event.bounce_type`。`bounce` イベントの `type`
 * @property occurredAt SendGrid 側の発生時刻。`received_at` ではない
 */
data class AddressEvent(
    val event: String,
    val bounceType: String?,
    val occurredAt: OffsetDateTime,
)

/**
 * 1 アドレス分の導出状態。`email_address_state` の 1 行に対応する（`docs/SPEC.md` §3.2）。
 *
 * @property sendable このアドレスへ送ってよいか
 * @property reasonCode hard_bounce / spam_report / unsubscribe。`invalid` を付けるのは
 *   `SuppressionReconciler` であって、この層ではない（同 §4.5）
 * @property lastFailureAt 最後に失敗した時刻。**購読解除は含まない**
 * @property softBounceCount 一時的な不達の件数
 */
data class AddressState(
    val sendable: Boolean,
    val reasonCode: String?,
    val lastFailureAt: OffsetDateTime?,
    val softBounceCount: Int,
) {
    companion object {
        /**
         * 対象アドレスの**全イベント**から状態を決める。
         *
         * **単発のイベントで遷移させない**（`CLAUDE.md`「絶対に変更しないこと」5）。
         * 下の `when` が見ているのは「その種類が 1 件でもあるか」だけで、
         * **並び順にも件数にも依存しない**——だから `delivered` の後に `processed` が届いても、
         * 同じイベントを二度読んでも、結果は変わらない。呼び出し側が `ORDER BY` を
         * 書いていないのは、この性質の裏返しである。
         *
         * ## 見ないイベント
         *
         * **無視も判断なので、ここに書く。書いていないと後から足される。**
         *
         * - `group_unsubscribe` / `group_resubscribe` — **グループ単位の購読可否は
         *   SendGrid 側が持つ。** この状態が答えるのは「このアドレスへ送ってよいか」であって、
         *   粒度が違う。グループの解除で全体を止めると、**送れるはずのメールが送れなくなる。**
         *   `group_resubscribe` が存在することは、**この状態が単調でない**ことも意味する——
         *   下の優先度は一度落ちたら戻らない形なので、グループ単位の出入りは最初から表現できない
         * - `open` / `click` — 配信の可否と関係が無い
         * - `account_status_change` — **送信者アカウント**の状態であって、宛先アドレスの話ではない
         */
        fun from(events: List<AddressEvent>): AddressState {
            // 優先度は docs/SPEC.md §4.4。上から順に見て、最初に当たったものを採る
            val (sendable, reasonCode) =
                when {
                    events.any { it.event == SPAMREPORT } -> false to "spam_report"

                    events.any { it.isHardBounce } -> false to "hard_bounce"

                    // dropped より前に置く。dropped は「抑制リストに載っていたので捨てた」という
                    // **結果**で、unsubscribe はその**原因**である。原因のほうが情報を持つ
                    events.any { it.event == UNSUBSCRIBE } -> false to "unsubscribe"

                    // 送れないことは分かるが、**理由は他のイベントが持つ。**
                    // reason の文字列（"Bounced Address" など）を読んで振り分けない
                    // ——SendGrid が文言を変えた日に、黙って壊れる。原因のイベントを
                    // 取りこぼしていた場合は SuppressionReconciler が補完する（同 §4.5）
                    events.any { it.event == DROPPED } -> false to null

                    else -> true to null
                }
            return AddressState(
                sendable = sendable,
                reasonCode = reasonCode,
                lastFailureAt = events.filter { it.isFailure }.maxOfOrNull { it.occurredAt },
                softBounceCount = events.count { it.isSoftBounce },
            )
        }
    }
}

private const val SPAMREPORT = "spamreport"
private const val BOUNCE = "bounce"
private const val DROPPED = "dropped"
private const val DEFERRED = "deferred"
private const val UNSUBSCRIBE = "unsubscribe"

/** `bounce` イベントの `type`。**受け取り側が一時的に拒んだ**という意味で、恒久的な不達ではない。 */
private const val BLOCKED = "blocked"

/**
 * 恒久的な不達。
 *
 * **`blocked` でないことで判定している。** `docs/SPEC.md` §4.4 は `bounce`(type=bounce) と
 * 書いているが、そのとおり `== "bounce"` で書くと、**`type` が落ちた日に恒久的な不達を
 * 一時扱いにする**——届かないアドレスへ送り続けることになる。
 * 分類できないものは、送らない側へ倒す。
 */
private val AddressEvent.isHardBounce: Boolean
    get() = event == BOUNCE && bounceType != BLOCKED

/**
 * 一時的な不達。**`sendable` は落とさない**——恒久的な不達と混ぜない。
 */
private val AddressEvent.isSoftBounce: Boolean
    get() = event == DEFERRED || (event == BOUNCE && bounceType == BLOCKED)

/**
 * `last_failure_at` を動かすもの。
 *
 * **`unsubscribe` は入らない。** 購読解除は失敗ではない——**列名が意味するものを守る。**
 */
private val AddressEvent.isFailure: Boolean
    get() = isHardBounce || isSoftBounce || event == SPAMREPORT || event == DROPPED
