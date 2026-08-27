package io.propagandist.recibir.webhook

import io.propagandist.recibir.support.TestKeyPair
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.security.Signature
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `SendGridSignatureVerifier` のテスト。観点は `docs/repository-layout.md` の一覧に対応する。
 *
 * **外部を一切使わない。** SendGrid のアカウントもネットワークも DB も要らず、
 * 鍵ペアはその場で生成して自分で署名する（観点 15）。
 */
class SendGridSignatureVerifierTest {
    private val keyPair = TestKeyPair.generate()
    private val publicKeyBase64 = TestKeyPair.publicKeyBase64(keyPair)

    private val now = Instant.parse("2026-08-27T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val timestamp = now.epochSecond.toString()

    private val body = """[{"email":"user@example.com","event":"delivered","sg_event_id":"abc"}]""".toByteArray()

    private fun verifier(
        publicKey: String = publicKeyBase64,
        at: Clock = clock,
    ) = SendGridSignatureVerifier(publicKey, at)

    // 観点 1
    @Test
    fun `正しい署名と正しい timestamp なら通る`() {
        val signature = TestKeyPair.sign(keyPair, timestamp, body)
        assertTrue(verifier().verify(signature, timestamp, body))
    }

    // 観点 2
    @Test
    fun `ボディを 1 バイト変えると落ちる`() {
        val signature = TestKeyPair.sign(keyPair, timestamp, body)
        val tampered = body.copyOf().also { it[it.lastIndex] = '!'.code.toByte() }
        assertFalse(verifier().verify(signature, timestamp, tampered))
    }

    // 観点 3。この 1 本のために ByteArray で受けている（CLAUDE.md「絶対に変更しないこと」2）
    @Test
    fun `整形されたボディを JSON として往復させると落ちる`() {
        val formatted =
            """
            [
              {
                "email": "user@example.com",
                "event": "delivered",
                "sg_event_id": "abc"
              }
            ]
            """.trimIndent().toByteArray()
        val signature = TestKeyPair.sign(keyPair, timestamp, formatted)

        val mapper = JsonMapper.builder().build()
        val roundTripped = mapper.writeValueAsBytes(mapper.readTree(formatted))

        // JSON としては等価である。それでも署名の入力としては別物になる
        assertEquals(mapper.readTree(formatted), mapper.readTree(roundTripped))
        assertFalse(formatted.contentEquals(roundTripped))
        assertFalse(verifier().verify(signature, timestamp, roundTripped))
    }

    /**
     * 観点 3 の裏側。**往復しても通ってしまう入力がある。**
     *
     * body は空白を含まずキーの順序も変わらないので、往復してもバイト列が同じになる。
     * つまり「パースして再シリアライズする実装」は、**入力の形しだいで通ったり落ちたりする**。
     * 常に落ちるなら実装した日に気づくが、**たまたま通ると、動いていると誤認したまま出す**。
     * SendGrid が送るボディの形は、こちらでは決められない。
     */
    @Test
    fun `コンパクトなボディは往復しても通ってしまう`() {
        val signature = TestKeyPair.sign(keyPair, timestamp, body)

        val mapper = JsonMapper.builder().build()
        val roundTripped = mapper.writeValueAsBytes(mapper.readTree(body))

        assertTrue(body.contentEquals(roundTripped))
        assertTrue(verifier().verify(signature, timestamp, roundTripped))
    }

    // 観点 4
    @Test
    fun `timestamp だけを変えると落ちる`() {
        val signature = TestKeyPair.sign(keyPair, timestamp, body)
        val other = (now.epochSecond - 1).toString()
        assertFalse(verifier().verify(signature, other, body))
    }

    // 観点 5
    @Test
    fun `timestamp とボディの連結順を逆にした署名は通らない`() {
        val reversed =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(keyPair.private)
                update(body)
                update(timestamp.toByteArray(Charsets.UTF_8))
                Base64.getEncoder().encodeToString(sign())
            }
        assertFalse(verifier().verify(reversed, timestamp, body))
    }

    // 観点 6。境界の内側は通り、外側は落ちる
    @Test
    fun `timestamp が 10 分より古いと落ちる`() {
        val exactly = now.minusSeconds(600).epochSecond.toString()
        assertTrue(verifier().verify(TestKeyPair.sign(keyPair, exactly, body), exactly, body))

        val tooOld = now.minusSeconds(601).epochSecond.toString()
        assertFalse(verifier().verify(TestKeyPair.sign(keyPair, tooOld, body), tooOld, body))
    }

    // 観点 7。片側だけ見ると、未来の値を送るだけで窓を素通りできる
    @Test
    fun `timestamp が 10 分より未来だと落ちる`() {
        val exactly = now.plusSeconds(600).epochSecond.toString()
        assertTrue(verifier().verify(TestKeyPair.sign(keyPair, exactly, body), exactly, body))

        val tooNew = now.plusSeconds(601).epochSecond.toString()
        assertFalse(verifier().verify(TestKeyPair.sign(keyPair, tooNew, body), tooNew, body))
    }

    // 観点 8
    @Test
    fun `別の鍵ペアで署名したものは落ちる`() {
        val attacker = TestKeyPair.generate()
        val signature = TestKeyPair.sign(attacker, timestamp, body)
        assertFalse(verifier().verify(signature, timestamp, body))
    }

    // 観点 9。例外を投げず false に倒れる
    @Test
    fun `公開鍵が不正な Base64 でも例外を投げずに落ちる`() {
        val signature = TestKeyPair.sign(keyPair, timestamp, body)
        assertFalse(verifier(publicKey = "not base64 at all!!").verify(signature, timestamp, body))
    }

    @Test
    fun `公開鍵が Base64 として読めても鍵でなければ落ちる`() {
        val signature = TestKeyPair.sign(keyPair, timestamp, body)
        val notAKey = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        assertFalse(verifier(publicKey = notAKey).verify(signature, timestamp, body))
    }

    // 観点 10
    @Test
    fun `署名が不正な DER でも例外を投げずに落ちる`() {
        val notDer = Base64.getEncoder().encodeToString(byteArrayOf(0x00, 0x01, 0x02))
        assertFalse(verifier().verify(notDer, timestamp, body))
    }

    @Test
    fun `署名が Base64 として読めなくても例外を投げずに落ちる`() {
        assertFalse(verifier().verify("!!! not base64 !!!", timestamp, body))
    }

    // 観点 11
    @Test
    fun `timestamp が数値でなくても例外を投げずに落ちる`() {
        val signature = TestKeyPair.sign(keyPair, timestamp, body)
        assertFalse(verifier().verify(signature, "not-a-number", body))
    }

    // 観点 12
    @Test
    fun `空のボディ・空の署名・空の timestamp のどれでも落ちる`() {
        val signature = TestKeyPair.sign(keyPair, timestamp, body)
        assertFalse(verifier().verify(signature, timestamp, ByteArray(0)))
        assertFalse(verifier().verify("", timestamp, body))
        assertFalse(verifier().verify(signature, "", body))
    }

    // 観点 13。失敗の種類が違っても、返るものは同じ false だけである
    @Test
    fun `失敗の理由が戻り値から読み分けられない`() {
        val valid = TestKeyPair.sign(keyPair, timestamp, body)
        val failures =
            listOf(
                verifier().verify(valid, timestamp, ByteArray(0)),
                verifier().verify("!!!", timestamp, body),
                verifier().verify(valid, "not-a-number", body),
                verifier().verify(valid, now.minusSeconds(601).epochSecond.toString(), body),
                verifier(publicKey = "broken").verify(valid, timestamp, body),
            )
        assertEquals(listOf(false, false, false, false, false), failures)
    }

    // 観点 14。JDK 標準の Provider だけで動く（BouncyCastle を入れていない）
    @Test
    fun `署名アルゴリズムが JDK 標準の Provider で解決される`() {
        val provider = Signature.getInstance("SHA256withECDSA").provider.name
        assertTrue(provider.startsWith("Sun"), "expected a JDK-bundled provider but was $provider")
    }
}
