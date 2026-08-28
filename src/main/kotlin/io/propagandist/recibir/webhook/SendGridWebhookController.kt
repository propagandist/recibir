package io.propagandist.recibir.webhook

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * SendGrid Event Webhook の受信口。
 *
 * 応答の対応表は `docs/SPEC.md` §4.2 が正本である。ここが持つのは署名検証と、
 * 通ったものを [SendGridEventIngestService] へ渡すことだけである。
 *
 * **JSON をパースしない。** `payload` に入れるのは生ノードであり（同 §4.3）、
 * パースの結果を要るのは投入側である。ここでパースすると同じ木を 2 回作ることになる。
 */
@RestController
class SendGridWebhookController(
    private val verifier: SendGridSignatureVerifier,
    private val ingestService: SendGridEventIngestService,
) {
    /**
     * イベント配列を受け取る。
     *
     * **ヘッダとボディをどれも必須にしていない。** 必須にすると、欠けたリクエストが
     * 400 になって 403 と分かれる——**応答の違いは、そのまま入力の分類器になる**
     * （`docs/repository-layout.md`「11' を落とせない理由」と同じ形）。
     * 欠けているものは、署名が合わないのと同じく 403 に倒す。
     *
     * **`consumes` も絞っていない。** 絞ると `Content-Type` 違いが 415 になり、これも分かれる。
     * **署名検証より手前に分岐を作らない。**
     */
    @PostMapping(PATH)
    fun receive(
        @RequestHeader(SIGNATURE_HEADER, required = false) signature: String?,
        @RequestHeader(TIMESTAMP_HEADER, required = false) timestamp: String?,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Void> {
        if (signature == null || timestamp == null || body == null) return forbidden()
        if (!verifier.verify(signature, timestamp, body)) return forbidden()

        // 解釈できないペイロードでも例外は上がってこない。200 で受け切る（上の KDoc）。
        // 上がってくるのは DB 障害で、それは 500 になって SendGrid に再送させる
        ingestService.ingest(body)
        return ResponseEntity.ok().build()
    }

    /**
     * 403 を、**ボディなしで**返す。
     *
     * 失敗理由を書かない（`CLAUDE.md`「絶対に変更しないこと」3）。
     * 署名不正・不正 Base64・不正 DER・タイムスタンプ範囲外は、外からは区別が付かない。
     */
    private fun forbidden(): ResponseEntity<Void> = ResponseEntity.status(HttpStatus.FORBIDDEN).build()

    companion object {
        /** 受信パス。`config/SecurityConfig` の webhook チェーンが覆う範囲に入る。 */
        const val PATH = "/webhooks/sendgrid/events"

        const val SIGNATURE_HEADER = "X-Twilio-Email-Event-Webhook-Signature"
        const val TIMESTAMP_HEADER = "X-Twilio-Email-Event-Webhook-Timestamp"
    }
}
