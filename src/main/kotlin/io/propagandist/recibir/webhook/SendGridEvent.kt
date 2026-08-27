package io.propagandist.recibir.webhook

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * SendGrid が POST してくるイベント配列の要素 1 件。
 *
 * カラムとの対応は `docs/SPEC.md` §3.1 が正本である。**射影は取り回しのためであって、
 * 永続化の正本ではない**——生 JSON は `sendgrid_event.payload` にそのまま入る
 * （`CLAUDE.md`「絶対に変更しないこと」4）。
 *
 * **この型で受信ボディを受け取らない。** 署名の検証対象は生バイト列であり、
 * JSON として往復させると通らなくなる（`docs/repository-layout.md` 観点 3）。
 * 受信は [SendGridWebhookController] が `ByteArray` で行い、この型を使うのは投入側である。
 *
 * @property sgEventId SendGrid が振る一意な ID。`sendgrid_event` の冪等キーになる
 * @property sgMessageId 接尾辞が付いたまま保持する。送信時の `X-Message-Id` とは一致しない（README 参照）
 * @property smtpId 受信 JSON でのキーは `smtp-id`
 * @property bounceType 受信 JSON でのキーは `type`。`bounce` / `blocked`
 * @property jobId 送信時に `custom_args` へ入れた値。SendGrid はこれをイベントの直下へ展開して送る
 * @property timestamp イベントの発生時刻（エポック秒）。**変換しない理由は下記**
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SendGridEvent(
    @JsonProperty("sg_event_id") val sgEventId: String,
    val email: String,
    val event: String,
    /**
     * **`Instant` にしない。** `Instant.ofEpochSecond` は範囲外の値で `DateTimeException` を投げ、
     * それは `RuntimeException` 系なので上へ抜ける（#29 で同じ形を踏んだ）。
     * ここで変換すると、**受信経路の中に落ちる場所ができる**。
     * `occurred_at` への変換は投入側で行う。
     */
    val timestamp: Long,
    @JsonProperty("sg_message_id") val sgMessageId: String? = null,
    @JsonProperty("smtp-id") val smtpId: String? = null,
    @JsonProperty("type") val bounceType: String? = null,
    val reason: String? = null,
    val status: String? = null,
    @JsonProperty("job_id") val jobId: String? = null,
)
