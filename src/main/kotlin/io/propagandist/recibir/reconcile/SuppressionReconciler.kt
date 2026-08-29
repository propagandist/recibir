package io.propagandist.recibir.reconcile

import io.propagandist.jooq.Tables.EMAIL_ADDRESS_STATE
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

/**
 * Suppression API と `email_address_state` を突き合わせる（`docs/SPEC.md` §4.5）。
 *
 * **Webhook の取りこぼしは起きる前提である**（`README.md`「Webhook を信頼しない」）。
 * 受信できなかったイベントは `sendgrid_event` に無く、したがって射影にも出ない
 * ——**送れないアドレスへ、静かに送り続ける。** ここが、その量を測る唯一の経路になる。
 *
 * ## 落とす方向にしか書かない
 *
 * **SendGrid 側に無いものを、こちらから消さない。** 抑制リストは運用者が手で外すことがあり、
 * それを「送ってよくなった」と読むと、**外した瞬間に過去のバウンス先へ送り始める。**
 *
 * ## `blocks` は状態を書き換えない
 *
 * `blocks` は**一時的な失敗**である（§4.4 は `blocked` で `sendable` を落とさない）。
 * リストに載ったアドレスは、SendGrid 側が解消を検知すれば外れる。ここで `sendable` を
 * 落とすと、**外れた日に戻す手段が無い**——§4.4 の優先度は一度落ちたら戻らない形なので、
 * 単調でないものを表現できない。
 *
 * だから `blocks` で数えるのは「**行が 1 つも無いアドレス**」だけとする。それは
 * 「そのアドレスのイベントを 1 件も受け取っていない」を意味し、**完全な取りこぼしの指標**になる。
 * 行があって `sendable = true` なのは正常な状態なので、数えない。
 *
 * ## 射影が上書きしうることを、分岐で塞がない
 *
 * [io.propagandist.recibir.event.EventProjector] は `sendgrid_event` に出てくるアドレスだけを
 * 回すので、**イベントが 1 件も無いアドレス**——取りこぼしの本体——には触れない。
 * イベントもあるアドレスで `invalid` が上書きされる筋は残るが、そのとき射影が書くのは
 * `hard_bounce` か `dropped` 由来の NULL で、**`sendable` は false のままである。**
 * 失われるのは `reason_code` の精度だけで、翌日の突き合わせが書き戻す。
 *
 * **射影側に「ここが書いた行は触らない」分岐を足すと、`email_address_state` が
 * `sendgrid_event` から再構築できなくなる**（同 §3.2）。二次テーブルであることをやめない。
 */
@Service
class SuppressionReconciler(
    private val apiClient: SuppressionApiClient,
    private val dsl: DSLContext,
) {
    /**
     * 4 つのリストを順に突き合わせる。
     *
     * **`zone` を書かないと JVM の既定に従い、動かす環境で時刻が変わる。**
     * 時刻そのものに意味は無いが、環境で変わらないことには意味がある。
     *
     * **1 つの source で失敗したら、残りも止まる。** 部分的に成功すると、
     * drift の数字が「どこまで見たか」を含まなくなる。例外はスケジューラが ERROR として残し、
     * 翌日また全部をやり直す——**日次バッチなので、1 日遅れても取りこぼしは消えない。**
     */
    @Scheduled(cron = DAILY_AT_0300, zone = "UTC")
    fun reconcile() {
        // 開始時点で 1 度だけ読む。**drift の判定を実行順に依存させない**
        // ——補完で書いた分を反映すると、後の source ほど差分が小さく見える
        val known = knownAddresses()
        val unsendable = unsendableAddresses()
        // 既に補完したアドレス。**優先度の高い source が書いたものを、後から上書きしない**
        // （並び順が優先度である。[SuppressionSource] の KDoc）
        val corrected = mutableSetOf<String>()

        SuppressionSource.entries.forEach { source ->
            val remote = apiClient.fetch(source)
            val reasonCode = source.reasonCode
            // reasonCode を持たないのは blocks だけで、そちらは「行があるか」だけを見る（KDoc）
            val drifted = remote - if (reasonCode == null) known else unsendable
            if (reasonCode != null) {
                (drifted - corrected).forEach { correct(it, reasonCode) }
                corrected += drifted
            }
            report(source, remote.size, drifted.size)
        }
    }

    /**
     * `email_address_state` にある全アドレス。`blocks` は、ここに無いものだけを差分と数える。
     *
     * **1 件ずつ問い合わせない。** 抑制リストは数千件になりうるので、そのぶん往復すると
     * 日次バッチが往復だけで終わる。**集合どうしの差を取るほうが短い。**
     */
    private fun knownAddresses(): Set<String> =
        dsl
            .select(EMAIL_ADDRESS_STATE.EMAIL)
            .from(EMAIL_ADDRESS_STATE)
            .fetchSet(EMAIL_ADDRESS_STATE.EMAIL)

    /** 送れない印が付いているアドレス。`blocks` 以外は、ここに無いものを差分と数える。 */
    private fun unsendableAddresses(): Set<String> =
        dsl
            .select(EMAIL_ADDRESS_STATE.EMAIL)
            .from(EMAIL_ADDRESS_STATE)
            .where(EMAIL_ADDRESS_STATE.SENDABLE.isFalse)
            .fetchSet(EMAIL_ADDRESS_STATE.EMAIL)

    /**
     * 1 行を、送れない側へ倒す。
     *
     * **`last_failure_at` と `soft_bounce_count` を触らない。** どちらも Webhook 由来の値で、
     * Suppression API の `created` は「リストに載った時刻」である（[SuppressionApiClient]）。
     * **射影と違って `newRecord` をまるごと渡さないのはこのため**——あちらは全列を導出し直すが、
     * ここが知っているのは 2 列だけである。
     */
    private fun correct(
        email: String,
        reasonCode: String,
    ) {
        val now = OffsetDateTime.now()
        dsl
            .insertInto(EMAIL_ADDRESS_STATE)
            .set(EMAIL_ADDRESS_STATE.EMAIL, email)
            .set(EMAIL_ADDRESS_STATE.SENDABLE, false)
            .set(EMAIL_ADDRESS_STATE.REASON_CODE, reasonCode)
            .set(EMAIL_ADDRESS_STATE.UPDATED_AT, now)
            .onConflict(EMAIL_ADDRESS_STATE.EMAIL)
            .doUpdate()
            .set(EMAIL_ADDRESS_STATE.SENDABLE, false)
            .set(EMAIL_ADDRESS_STATE.REASON_CODE, reasonCode)
            .set(EMAIL_ADDRESS_STATE.UPDATED_AT, now)
            .execute()
    }

    /**
     * 突き合わせた結果を残す。
     *
     * **差分が無ければ `reconcile.drift` を出さない。** 毎日 WARNING が出続けると、
     * 監視の側に**無視する習慣**ができる。突き合わせが動いていること自体は、
     * `event` キーを持たない素の行が担う——`docs/SPEC.md` §4.7 の表は
     * 「運用が条件にするもの」の一覧なので、そこを増やさない。
     */
    private fun report(
        source: SuppressionSource,
        fetched: Int,
        drifted: Int,
    ) {
        log.info("reconciled {} entries from {}", fetched, source.label)
        if (drifted == 0) return
        log
            .atWarn()
            .addKeyValue("event", "reconcile.drift")
            .addKeyValue("source", source.label)
            .addKeyValue("count", drifted)
            // **アドレスは載せない**（同 §4.7）。件数で足り、どれかを知るには DB を引く
            .log("found addresses that the webhook did not deliver")
    }

    private companion object {
        val log = LoggerFactory.getLogger(SuppressionReconciler::class.java)

        /** 毎日 03:00。**時刻そのものに意味は無い**（日次 4 リクエストはレート制限に触れない）。 */
        const val DAILY_AT_0300 = "0 0 3 * * *"
    }
}
