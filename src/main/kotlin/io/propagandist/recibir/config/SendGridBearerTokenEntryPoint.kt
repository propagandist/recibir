package io.propagandist.recibir.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint
import org.springframework.security.web.AuthenticationEntryPoint

/**
 * トークンを検証できなかったとき、**ボディにエラーコードを書いて**返す。
 *
 * **SendGrid は一度取得したアクセストークンをキャッシュして使い回す。** 期限切れや不正を
 * 検知させるには、4xx を返すだけでなく**レスポンスボディに** `invalid_request` /
 * `invalid_token` / `insufficient_scope` のいずれかを含める必要がある
 * （2026-08-30 に SendGrid のドキュメントで確認）。
 *
 * Spring Security 標準の [BearerTokenAuthenticationEntryPoint] は `WWW-Authenticate` ヘッダに
 * しか error を載せず、**ボディを 1 バイトも書かない**（2026-08-30 に実装を確認）。
 * そのままだと SendGrid はトークンを取り直さず、**401 を出し続けたままイベントが失われる。**
 * `README.md`「踏み抜きやすい箇所」が挙げているのはここである。
 *
 * ## ヘッダを自分で組み立てない
 *
 * 標準実装へ委譲してステータスとヘッダを書かせ、**その後ろにボディを足すだけ**にしてある。
 * `WWW-Authenticate` の書式は Security の版で増える（`realm` / `error_description` / `scope` /
 * RFC 9728 の `resource_metadata`）ので、**写すと版が上がった日に黙ってずれる。**
 *
 * ## トークンが無いときはボディを書かない
 *
 * 標準実装は、[OAuth2AuthenticationException] でない例外に `error` を出さない
 * ——RFC 6750 §3.1 が、トークンを送ってこなかった要求には error を含めないと定めている。
 * **ここも書かない。SendGrid は設定すれば必ず付けてくる**ので、無いのは設定ミスか攻撃であり、
 * **取り直させる相手がいない。**
 */
internal class SendGridBearerTokenEntryPoint : AuthenticationEntryPoint {
    private val delegate = BearerTokenAuthenticationEntryPoint()

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        delegate.commence(request, response, authException)
        val errorCode = (authException as? OAuth2AuthenticationException)?.error?.errorCode

        // **レスポンスとログで扱いを変える。** 署名検証と同じ判断である（docs/SPEC.md §4.1）
        // ——ボディへ書くのは SendGrid にトークンを取り直させるためで、ログは運用者だけが見る。
        // 理由を残さないと、認可サーバの設定ミスと攻撃が区別できない。
        //
        // event キーを増やさず webhook.rejected に相乗りさせている。§4.7 の表は
        // 「運用が条件にするもの」の一覧なので、増やさずに済むなら増やさない
        log
            .atWarn()
            .addKeyValue("event", "webhook.rejected")
            .addKeyValue("reason", errorCode ?: "missing_token")
            .log("rejected a webhook request whose access token did not verify")

        if (errorCode == null) return
        // マッパーを注入しない。キーは 1 つで、値は Spring が組み立てた語彙である
        // （BearerTokenError が RFC 6750 の許容文字を検証している）
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.write("""{"error":"$errorCode"}""")
    }

    private companion object {
        val log = LoggerFactory.getLogger(SendGridBearerTokenEntryPoint::class.java)
    }
}
