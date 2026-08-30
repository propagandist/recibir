package io.propagandist.recibir.config

import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.web.SecurityFilterChain

/**
 * フィルタチェーンを用途ごとに分ける。
 *
 * 分け方の正本は `docs/SPEC.md` §4.6 である。
 *
 * ## 受信口のチェーンは 1 本である
 *
 * SPEC の表は webhook と oauth を別の行に置いているが、**実装は 1 本になる。**
 * `securityMatcher` が等しいチェーンを 2 本登録すると、Spring Security が起動時に
 * `UnreachableFilterChainException` で止める——`WebSecurityFilterChainValidator` が
 * **同じ matcher を持つ隣り合ったチェーンを弾く**（2026-08-30 実測）。
 *
 * OAuth を設定したときは、この 1 本の**中身が変わる**。
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {
    /**
     * 受信口のチェーン。
     *
     * OAuth を設定していないとき、**認証は署名検証が担う**ので誰でも通す。
     * `csrf` を切るのは SendGrid がトークンを持たないためで、セッションを作らないのは
     * **1 回きりの POST に状態が要らない**ためである。
     *
     * ## OAuth の有無を [JwtDecoder] の有無で決める
     *
     * **設定のキーを自分で読まない。** Boot が decoder を組めたかどうかだけを見る
     * （`issuer-uri` / `jwk-set-uri` / `public-key-location` のいずれかから組まれる）。
     * キー名をここに書くと、**Boot が読む場所とこちらが見る場所の 2 つになり**、
     * 片方だけ変わった日に「設定したのに効いていない」が起きる。
     *
     * **推奨は `issuer-uri` である**（`README.md`「動かす」）。あれだけが `iss` クレームの
     * 検証を付ける——`jwk-set-uri` は鍵の在り処しか言わないので、**別の発行者が同じ鍵で
     * 署名したトークンを見分けられない。**
     *
     * ## OAuth は署名検証の代替ではない
     *
     * トークンが通っても [io.propagandist.recibir.webhook.SendGridWebhookController] は
     * 署名検証を通す（`CLAUDE.md`「絶対に変更しないこと」1）。OAuth が見るのは**誰が
     * 投げてきたか**で、署名が見るのは**ボディが途中で変わっていないか**である。
     *
     * ## スコープを要求しない
     *
     * 要求するとスコープ名が設定項目になり（`docs/SPEC.md` §5）、`insufficient_scope`（403）の
     * 経路も足すことになる——**認可サーバは利用者のもので、こちらが名前を決められない。**
     *
     * ## 認可サーバへ繋がらないときは 403 になる
     *
     * `issuer-uri` から作られるのは `SupplierJwtDecoder` で、discovery は**最初の検証まで
     * 遅延する**。**起動は通る**ので、落ちていることは最初のトークンが来たときに分かる。
     *
     * そのとき出る `JwtDecoderInitializationException` は `AuthenticationException` ではない
     * ——**entry point を通らず**、Tomcat のエラーページへ抜けて **403 とボディなし**になる
     * （2026-08-30 実測）。**署名検証の失敗と同じ番号**である。
     *
     * **捕まえて番号を振り分けていない。** 失敗理由をレスポンスに出さないという方針
     * （`CLAUDE.md`「絶対に変更しないこと」3）とは元々整合しており、運用者が見るのは
     * **ERROR で出るスタックトレース**である。SendGrid は非 2xx を再送するので、
     * 認可サーバが戻れば取りこぼさない。
     */
    @Bean
    @Order(1)
    fun webhookFilterChain(
        http: HttpSecurity,
        decoders: ObjectProvider<JwtDecoder>,
    ): SecurityFilterChain {
        val decoder = decoders.getIfAvailable()
        http {
            securityMatcher(WEBHOOK_PATHS)
            csrf { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            // 設定していなければ誰でも通す——認証は署名検証が担う。設定していれば、
            // トークンを検証できたものだけを通す
            authorizeHttpRequests {
                if (decoder == null) authorize(anyRequest, permitAll) else authorize(anyRequest, authenticated)
            }
            decoder?.let { available ->
                oauth2ResourceServer {
                    authenticationEntryPoint = SendGridBearerTokenEntryPoint()
                    jwt { jwtDecoder = available }
                }
            }
        }
        return http.build()
    }

    /**
     * 受信口**以外**のチェーン。
     *
     * **上のチェーンだけにしない。** `securityMatcher` を持つチェーンを 1 本だけ置くと、
     * そのパターンに当たらないリクエストは**どのチェーンにも入らず、Security を素通りする**。
     * 今は守る対象が無くても、エンドポイントを足した日に穴が開く。
     * このリポジトリは分類 A である（`CLAUDE.md`「セキュリティ」）。
     *
     * **認証方式を選んでいない。** ここに `httpBasic` や `formLogin` を足すと、
     * 誰も使わない認証機構と、その設定項目が増える（`docs/SPEC.md` §5）。
     * 通さないことだけを決めてある。
     */
    @Bean
    @Order(2)
    fun appFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            authorizeHttpRequests { authorize(anyRequest, authenticated) }
        }
        return http.build()
    }

    private companion object {
        /** `docs/SPEC.md` §4.6 の対象。受信パスそのものは `SendGridWebhookController.PATH`。 */
        const val WEBHOOK_PATHS = "/webhooks/sendgrid/**"
    }
}
