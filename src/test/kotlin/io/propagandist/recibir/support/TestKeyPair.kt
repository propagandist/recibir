package io.propagandist.recibir.support

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * テスト用の EC P-256 鍵ペアと、それで署名を作るヘルパー。
 *
 * 署名検証をスキップする経路は作らない（`CLAUDE.md`「絶対に変更しないこと」1）。
 * ローカルでもテストでも、鍵ペアをその場で生成して自分で署名する。
 * SendGrid のアカウントもネットワークも要らない。
 */
object TestKeyPair {
    /** SendGrid が使う曲線と同じ P-256（secp256r1）で鍵ペアを作る。 */
    fun generate(): KeyPair =
        KeyPairGenerator
            .getInstance("EC")
            .apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()

    /**
     * 公開鍵を、SendGrid が配る形式に揃えて返す。
     *
     * `getEncoded()` が返すのは X.509 SubjectPublicKeyInfo の DER で、
     * それを Base64 にしたものが設定に入る値になる。
     */
    fun publicKeyBase64(keyPair: KeyPair): String = Base64.getEncoder().encodeToString(keyPair.public.encoded)

    /**
     * `timestamp` と `body` をこの順で連結して署名し、Base64 で返す。
     *
     * 連結の向きは SendGrid の実装に合わせてある。逆にすると検証は通らない。
     */
    fun sign(
        keyPair: KeyPair,
        timestamp: String,
        body: ByteArray,
    ): String {
        val der =
            Signature
                .getInstance("SHA256withECDSA")
                .apply {
                    initSign(keyPair.private)
                    update(timestamp.toByteArray(Charsets.UTF_8))
                    update(body)
                }.sign()
        return Base64.getEncoder().encodeToString(der)
    }
}
