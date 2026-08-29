package io.propagandist.recibir.event

import io.propagandist.jooq.Tables.EMAIL_ADDRESS_STATE
import io.propagandist.jooq.Tables.SENDGRID_EVENT
import io.propagandist.jooq.tables.records.EmailAddressStateRecord
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

/**
 * `sendgrid_event` から `email_address_state` を作り直す（`docs/SPEC.md` §4.4）。
 *
 * 状態をどう決めるかは [AddressState] が持つ。ここが持つのは**どれを・いつ作り直すか**である。
 *
 * ## 処理済みを追わない
 *
 * **「未処理イベント」を記録しない。** 状態は対象アドレスの全イベントから決まるので、
 * **同じアドレスを何度作り直しても結果は同じ**である。だから直近に受信したイベントの
 * アドレスを集めて作り直せば足りる。
 *
 * 窓（[WINDOW]）はポーリング間隔より長い。**重なるのは無駄ではなく、1 回失敗しても
 * 次の回で拾えるということ**である。長く止まった後の取りこぼしは [rebuildAll] が受け皿になる。
 *
 * 処理済みの id を持つテーブルは足していない。**捨てて作り直せない三つ目のテーブル**が
 * 増えるためで、`sendgrid_event` に処理済みの列を足さないのも同じ理由である
 * （正本を append-only に保てなくなる。同 §3.1）。
 *
 * **窓も間隔も設定項目にしない**（同 §5）。署名検証の許容ずれと同じ扱いである。
 */
@Service
class EventProjector(
    private val dsl: DSLContext,
) {
    /**
     * 直近の窓に受信したイベントのアドレスを作り直す。
     *
     * **`received_at` で絞る。** `occurred_at` ではない——遅れて届いたイベントは
     * SendGrid 側の時刻が古いので、そちらで絞ると窓から外れて永久に拾えなくなる。
     */
    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
    fun projectRecent() {
        // 窓の起点はアプリの時計で作る。DB の時計と 2 つになるが、**そのずれは窓の重なりが
        // 吸収する**——重なりを取ってあるのは、そもそも 1 回失敗しても次で拾うためである
        val since = OffsetDateTime.now().minus(WINDOW)
        dsl
            .selectDistinct(SENDGRID_EVENT.EMAIL)
            .from(SENDGRID_EVENT)
            .where(SENDGRID_EVENT.RECEIVED_AT.ge(since))
            .fetch(SENDGRID_EVENT.EMAIL)
            .forEach { project(it) }
    }

    /**
     * `sendgrid_event` に出てくる全アドレスを作り直す。**解釈を直した日のためにある。**
     *
     * **イベントが 1 件も無い状態行は触らない。** `SuppressionReconciler` が
     * Suppression API を正として先に立てた行がありうる（`docs/er.md` の多重度 3 行目）。
     * ここが消すと、Webhook を取りこぼしたアドレスの記録が毎回消えることになる。
     *
     * @return 作り直したアドレスの件数
     */
    fun rebuildAll(): Int {
        val emails =
            dsl
                .selectDistinct(SENDGRID_EVENT.EMAIL)
                .from(SENDGRID_EVENT)
                .fetch(SENDGRID_EVENT.EMAIL)
        emails.forEach { project(it) }
        // event キーを持たない素のログにしてある。docs/SPEC.md §4.7 の表は
        // 運用が監視するものを並べたもので、起動時の 1 回きりの記録はそこに要らない
        log.info("rebuilt {} addresses", emails.size)
        return emails.size
    }

    /**
     * 1 アドレスを、そのアドレスの**全イベント**から作り直す。
     *
     * **`ORDER BY` を書いていない。** [AddressState.from] が並び順に依存しないためで、
     * 書けば「順序に意味がある」と読まれる。
     *
     * **トランザクションを張っていない。** 読みと書きの間に新しいイベントが入っても、
     * 次のポーリングが窓の重なりで拾い直す。ここで整合を取ろうとすると、
     * **窓が重なっている理由と二重に手当てすることになる。**
     */
    fun project(email: String) {
        val events =
            dsl
                .select(SENDGRID_EVENT.EVENT, SENDGRID_EVENT.BOUNCE_TYPE, SENDGRID_EVENT.OCCURRED_AT)
                .from(SENDGRID_EVENT)
                .where(SENDGRID_EVENT.EMAIL.eq(email))
                .fetch { AddressEvent(it.value1(), it.value2(), it.value3()) }

        val next = AddressState.from(events)
        val current = dsl.fetchOne(EMAIL_ADDRESS_STATE, EMAIL_ADDRESS_STATE.EMAIL.eq(email))
        // **変わっていなければ書かない。** 窓が重なる以上、同じアドレスは何度も通る——
        // 毎回書くと updated_at が 1 分ごとに動き、「いつ状態が変わったか」が読めなくなる。
        // 下の address.unsendable が毎分出ないのも、この分岐のおかげである
        if (current != null && current.matches(next)) return

        write(email, next)
        if (next.sendable) return
        // **送れなくなったことに気づく手段はこの 1 行である**（#5）。
        // 出るのは状態が実際に変わったときだけで、全件再構築の直後には全件が出る
        // ——それは記録として正しい。**通知の抑制は監視基盤の仕事である**（docs/SPEC.md §4.7）
        log
            .atWarn()
            .addKeyValue("event", "address.unsendable")
            .addKeyValue("email", maskEmail(email))
            .addKeyValue("reason_code", next.reasonCode)
            .log("an address became unsendable")
    }

    /**
     * 1 行を UPSERT する。
     *
     * **同じ record を 2 度渡している。** jOOQ は INSERT の値と ON CONFLICT の更新値を
     * 別に受けるが、ここでは**全列を導出し直している**ので、どちらも同じ値になる。
     * 列を並べ直すと、片方に足し忘れる形の壊れ方ができる。
     */
    private fun write(
        email: String,
        state: AddressState,
    ) {
        val record =
            dsl.newRecord(EMAIL_ADDRESS_STATE).apply {
                this.email = email
                sendable = state.sendable
                reasonCode = state.reasonCode
                lastFailureAt = state.lastFailureAt
                softBounceCount = state.softBounceCount
                // 「状態が変わった時刻」になる。上の分岐が、変わらないときに書かないためである
                updatedAt = OffsetDateTime.now()
            }
        dsl
            .insertInto(EMAIL_ADDRESS_STATE)
            .set(record)
            .onConflict(EMAIL_ADDRESS_STATE.EMAIL)
            .doUpdate()
            .set(record)
            .execute()
    }

    private companion object {
        val log = LoggerFactory.getLogger(EventProjector::class.java)

        /**
         * 直近の窓の広さ。**ポーリング間隔（1 分）より長く取る。**
         *
         * 規模の根拠は `docs/SPEC.md` §4.4 の「月数万通規模」で、1 分ごとに数件を読む形になる。
         */
        val WINDOW: Duration = Duration.ofMinutes(10)
    }
}

/**
 * 現在の行が、導出した状態と同じかどうか。
 *
 * **時刻は `Instant` で比べる。** `timestamptz` が保持するのは瞬間で、返るときのオフセットは
 * セッションのタイムゾーンに従う。`OffsetDateTime` 同士の `equals` はオフセットまで見るので、
 * **同じ瞬間を「変わった」と読んでしまう**。
 */
private fun EmailAddressStateRecord.matches(state: AddressState): Boolean =
    sendable == state.sendable &&
        reasonCode == state.reasonCode &&
        lastFailureAt?.toInstant() == state.lastFailureAt?.toInstant() &&
        softBounceCount == state.softBounceCount

/**
 * ログへ出すために、アドレスのローカル部を伏せる（`docs/SPEC.md` §4.7）。
 *
 * **1 文字も残さない。** 先頭の 1 文字を残す形にすると、**1 文字のローカル部がそのまま全部出る。**
 * ドメインだけ残せば「特定ドメインへの集中的な配信不能」は読めるので、目的は満たす
 * ——個人の特定は DB を引いて行う。
 *
 * **`@` を含まない値も受ける。** `email` 列に何が入るかを決めているのは SendGrid であって、
 * こちらではない。分けられなければ全部伏せる。区切りを**最後の** `@` で取るのも同じ理由で、
 * ローカル部に `@` を含む形が来ても、残るのはドメインだけになる。
 */
internal fun maskEmail(email: String): String {
    val at = email.lastIndexOf('@')
    return if (at < 0) MASK else MASK + email.substring(at)
}

private const val MASK = "***"
