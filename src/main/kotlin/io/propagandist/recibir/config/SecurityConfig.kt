package io.propagandist.recibir.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

/**
 * フィルタチェーンを用途ごとに分ける。
 *
 * 分け方の正本は `docs/SPEC.md` §4.6 である。同節は 3 本を挙げているが、
 * ここにあるのは webhook と app の 2 本で、**oauth チェーンはまだ無い**
 * （`docs/HANDOVER.md` の作業順序 10）。
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {
    /**
     * 受信口のチェーン。**認証は署名検証が担う**ので、ここでは誰でも通す。
     *
     * `csrf` を切るのは、SendGrid がトークンを持たないためである。
     * セッションを作らないのは、**1 回きりの POST に状態が要らない**ため。
     */
    @Bean
    @Order(1)
    fun webhookFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            securityMatcher(WEBHOOK_PATHS)
            csrf { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            authorizeHttpRequests { authorize(anyRequest, permitAll) }
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
