package io.propagandist.recibir.webhook

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * SendGrid Event Webhook の署名を検証する。
 *
 * 検証するのは `timestamp` と生ボディを連結したバイト列に対する ECDSA（P-256 / SHA-256）署名である。
 * 詳細は `docs/SPEC.md` §4.1、テストで押さえる観点は `docs/repository-layout.md` にある。
 *
 * **失敗理由を外へ出さない。** 署名不正・不正な Base64・不正な DER・タイムスタンプ範囲外は、
 * どれも `false` に倒れる。理由を返すと、公開鍵の設定ミスと攻撃の区別が呼び出し側から見えてしまい、
 * それはそのままレスポンスへ漏れる（`CLAUDE.md`「絶対に変更しないこと」3）。
 *
 * @param publicKeyBase64 SendGrid が配る公開鍵。Base64 の X.509 SubjectPublicKeyInfo
 * @param clock タイムスタンプの許容幅を測る時計。テストで差し替えるためだけに開けてある
 */
class SendGridSignatureVerifier(
    publicKeyBase64: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * 公開鍵はコンストラクタで一度だけ読む。読めなければ `null` のまま保持する。
     *
     * ここで例外を投げない。設定値が壊れているのは検証の失敗であって、
     * 起動を止める種類の異常ではない——**壊れた鍵で起動できてしまうことより、
     * 壊れた鍵で「検証に成功した」ことになるほうが危険**だからである。
     * 鍵が読めなければ、以降の検証はすべて `false` になる。
     */
    private val publicKey: PublicKey? = decodePublicKey(publicKeyBase64)

    /**
     * 署名を検証する。
     *
     * @param signatureBase64 `X-Twilio-Email-Event-Webhook-Signature` の値
     * @param timestamp `X-Twilio-Email-Event-Webhook-Timestamp` の値（エポック秒）
     * @param rawBody 受信したボディの生バイト列。**JSON としてパースし直したものを渡してはならない**
     *   ——空白・キーの順序・数値の表記が変わると、JSON として等価でも署名の入力としては別物になる
     * @return 検証に成功したときだけ `true`
     */
    fun verify(
        signatureBase64: String,
        timestamp: String,
        rawBody: ByteArray,
    ): Boolean {
        val key = publicKey ?: return false
        if (!isWithinAllowedSkew(timestamp)) return false

        return try {
            val signature = Base64.getDecoder().decode(signatureBase64)
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initVerify(key)
                update(timestamp.toByteArray(Charsets.UTF_8))
                update(rawBody)
                verify(signature)
            }
        } catch (e: GeneralSecurityException) {
            // 不正な DER、鍵と曲線の不一致など。理由は握り潰す（上の KDoc）
            false
        } catch (e: IllegalArgumentException) {
            // Base64 として読めない署名
            false
        }
    }

    /**
     * タイムスタンプが許容幅の内側かを見る。
     *
     * 過去と未来の両方を見る。**過去だけを見ると、未来の値を送るだけで窓を素通りできる。**
     */
    private fun isWithinAllowedSkew(timestamp: String): Boolean {
        val epochSeconds = timestamp.toLongOrNull() ?: return false
        val skew = Duration.between(Instant.ofEpochSecond(epochSeconds), clock.instant()).abs()
        return skew <= ALLOWED_SKEW
    }

    private fun decodePublicKey(base64: String): PublicKey? =
        try {
            val der = Base64.getDecoder().decode(base64)
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

    private companion object {
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

        /**
         * リプレイ対策の窓。**設定項目にしない**（`docs/SPEC.md` §5）。
         * 運用で緩められる形にすると、緩めた日にこの検証は意味を失う。
         */
        val ALLOWED_SKEW: Duration = Duration.ofMinutes(10)
    }
}
