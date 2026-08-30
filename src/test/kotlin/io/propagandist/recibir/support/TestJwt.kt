package io.propagandist.recibir.support

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Date

/**
 * テスト用の RSA 鍵ペアと、それで署名した JWT を作るヘルパー。
 *
 * **認可サーバを立てない。** SendGrid の OAuth は利用者が用意した認可サーバから
 * トークンを取る形だが（`config/SecurityConfig`）、**検証する側**を試すのに
 * 本物は要らない——鍵ペアをその場で作り、公開鍵から組んだ `JwtDecoder` に読ませる。
 * [TestKeyPair] が署名検証に対してやっているのと同じ構えである。
 *
 * ネットワークへは出ない（`docs/SPEC.md` §6）。
 */
object TestJwt {
    /** 発行者。値そのものに意味は無いが、**環境で変わらないことに意味がある。** */
    const val ISSUER = "https://issuer.example.test"

    /** 署名検証が EC なのに対し、ここは RSA である。**どちらも本物とは無関係の使い捨て**。 */
    fun generate(): KeyPair =
        KeyPairGenerator
            .getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()

    fun publicKey(keyPair: KeyPair): RSAPublicKey = keyPair.public as RSAPublicKey

    /**
     * 署名済みの JWT を 1 本作る。
     *
     * @param expiresAt 期限切れを試すテストは、ここへ過去の時刻を渡す。
     *   `NimbusJwtDecoder` は既定で期限を見るので、それだけで検証が落ちる
     */
    fun sign(
        keyPair: KeyPair,
        expiresAt: Instant = Instant.now().plusSeconds(300),
    ): String {
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer(ISSUER)
                .subject("sendgrid")
                .issueTime(Date.from(Instant.now().minusSeconds(60)))
                .expirationTime(Date.from(expiresAt))
                .build()
        val header =
            JWSHeader
                .Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .build()
        return SignedJWT(header, claims)
            .also { it.sign(RSASSASigner(keyPair.private)) }
            .serialize()
    }
}
