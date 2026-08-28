package io.propagandist.recibir.webhook

import io.propagandist.jooq.Tables.SENDGRID_EVENT
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Query
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneOffset

/**
 * 受信したイベント配列を `sendgrid_event` へ冪等に投入する。
 *
 * 設計の正本は `docs/SPEC.md` §4.3 である。**業務判断を一切しない**——
 * バウンス判定も状態遷移もここでは行わない（`CLAUDE.md`「絶対に変更しないこと」5）。
 * `email_address_state` を触るのは `EventProjector` である。
 *
 * **[SendGridSignatureVerifier] と違って Spring と jOOQ に依存している。**
 * あちらは素のまま持っていける形を保っているが、こちらは `DSLContext` と
 * トランザクション境界が本体なので、切り離しても持っていく先が無い。
 */
@Service
class SendGridEventIngestService(
    private val dsl: DSLContext,
    private val jsonMapper: JsonMapper,
) {
    /**
     * ボディを解釈して投入する。**署名の検証が済んだものだけを渡すこと。**
     *
     * **解釈できなければ、何もせずに戻る。** 呼び出し側は 200 を返す
     * （`docs/SPEC.md` §4.2 の 3 行目）。理由は [buildInsertQueries] にある。
     *
     * **DB が落ちた場合はここから例外が抜ける。** それは 500 になり、SendGrid が再送する
     * （同 §4.2 の 4 行目）。**入力が壊れている場合と、こちらが壊れている場合を混ぜない。**
     *
     * @param rawBody 受信した生バイト列
     */
    @Transactional
    fun ingest(rawBody: ByteArray) {
        val queries = buildInsertQueries(rawBody) ?: return
        if (queries.isEmpty()) return

        // 1 リクエストにつき単一の PreparedStatement へ集約する（docs/SPEC.md §4.3 / §6）。
        // 下の insertQuery が常に同じ列を並べるので、SQL は件数によらず 1 種類で済む。
        dsl.batch(queries).execute()
    }

    /**
     * 配列の各要素を INSERT 文に変える。**解釈できなければ `null` を返す。**
     *
     * 不正な JSON・配列でない・`sg_event_id` が無い・`timestamp` が変換できない——
     * **どれも「再送されても直らない」という点で同じ**なので、1 つの経路にまとめてある。
     * 分けても呼び出し側の応答は変わらず（どれも 200）、分岐だけが増える。
     *
     * **捕まえる例外の型に注意する。** Jackson 3 の `JacksonException` は
     * `RuntimeException` 系で、`IOException` を継承していない
     * （`CLAUDE.md`「Spring Boot 4 の注意点」）。`DateTimeException` も同様に
     * 検査例外ではない。**素朴に書くとどちらも素通りして 500 になる**——
     * 再送されても直らない入力に 500 を返すと、SendGrid の再送だけが積み上がる
     * （README「200 と 500 の切り分け」）。
     *
     * **退避先のテーブルは作っていない。** 解釈できないボディを保存しても、
     * 解釈できない以上そこから再処理はできない。気づく手段はログで、それは #5 が持つ。
     */
    private fun buildInsertQueries(rawBody: ByteArray): List<Query>? =
        try {
            val root = jsonMapper.readTree(rawBody)
            // values() を挟む。Jackson 3 の JsonNode は Iterable<JsonNode> ではなくなっており、
            // Jackson 2 のつもりで root を直接回すとコンパイルが通らない（2026-08-28 実測）
            if (root.isArray) root.values().map { insertQuery(it) } else null
        } catch (e: JacksonException) {
            null
        } catch (e: DateTimeException) {
            null
        }

    /**
     * イベント 1 件を INSERT 文にする。
     *
     * **`payload` には受け取ったノードをそのまま入れる。** [SendGridEvent] を書き戻さない
     * ——射影で捨てたフィールドは戻らない（README「生 JSON を捨てない」）。
     *
     * **`toString()` で再シリアライズしているが、ここでは害にならない。**
     * 署名の検証はもう済んでおり、`payload` を署名の入力に使うことはない。
     * キーの順序は保たれ、失われるのは空白だけである。
     * **同じ理屈を受信ボディに当てはめてはいけない**（`docs/repository-layout.md` 観点 3）。
     */
    private fun insertQuery(node: JsonNode): Query {
        val event = jsonMapper.treeToValue(node, SendGridEvent::class.java)
        return dsl
            .insertInto(SENDGRID_EVENT)
            .set(SENDGRID_EVENT.SG_EVENT_ID, event.sgEventId)
            .set(SENDGRID_EVENT.SG_MESSAGE_ID, event.sgMessageId)
            .set(SENDGRID_EVENT.SMTP_ID, event.smtpId)
            .set(SENDGRID_EVENT.EMAIL, event.email)
            .set(SENDGRID_EVENT.EVENT, event.event)
            .set(SENDGRID_EVENT.BOUNCE_TYPE, event.bounceType)
            .set(SENDGRID_EVENT.REASON, event.reason)
            .set(SENDGRID_EVENT.STATUS, event.status)
            .set(SENDGRID_EVENT.JOB_ID, event.jobId)
            // SendGrid 側の時刻。received_at は DB の DEFAULT に任せるので、ここでは触らない
            .set(SENDGRID_EVENT.OCCURRED_AT, Instant.ofEpochSecond(event.timestamp).atOffset(ZoneOffset.UTC))
            .set(SENDGRID_EVENT.PAYLOAD, JSONB.valueOf(node.toString()))
            // 冪等キー。SendGrid は同じイベントを再送する（docs/SPEC.md §3.1）
            .onConflict(SENDGRID_EVENT.SG_EVENT_ID)
            .doNothing()
    }
}
