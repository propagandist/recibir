package io.propagandist.recibir.reconcile

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.slf4j.LoggerFactory
import org.springframework.core.ParameterizedTypeReference
import org.springframework.web.client.RestClient

/**
 * Suppression API のリストを**全件**取る。
 *
 * **[SuppressionReconciler] と違って Spring に依存していない。**
 * 組み立ては [SuppressionApiConfiguration] が持つ。受信側の
 * [io.propagandist.recibir.webhook.SendGridSignatureVerifier] と同じ構えである
 * ——**このリポジトリは読んで持っていかれることが目的**なので、外部 API を叩く部分は
 * フレームワークから切り離しておく。
 *
 * ## 期間で絞らない
 *
 * `start_time` / `end_time` を使わない。**期間で絞ると、取りこぼしは窓の外に落ちたまま
 * 永久に見つからない**——それは、まさに検出したいものである。
 *
 * **「前回いつ実行したか」も記録しない。** 記録すれば、捨てて作り直せない状態が増える
 * （#34 で退避テーブルを、#36 で処理済みテーブルを却下したのと同じ理由）。
 * 抑制リストは送信量ではなく**失敗した分だけ**溜まるので、全件でも数ページに収まる。
 */
class SuppressionApiClient(
    private val restClient: RestClient,
) {
    /**
     * 1 つのリストを全件取り、アドレスの集合にする。
     *
     * **`email` 以外を読まない。** 突き合わせに要るのはアドレスだけで、`reason` の文字列は
     * 読まない（`docs/SPEC.md` §4.4 と同じ判断——SendGrid が文言を変えた日に壊れる）。
     * `reason_code` は [SuppressionSource] が持ち、応答からは決めない。
     *
     * **`created` も読まない。** `email_address_state.last_failure_at` は Webhook 由来の
     * 発生時刻で、こちらの `created` は**リストに載った時刻**である。埋めると列の意味が 2 つになる。
     */
    fun fetch(source: SuppressionSource): Set<String> {
        val emails = mutableSetOf<String>()
        var offset = 0
        repeat(MAX_PAGES) {
            val page = page(source, offset)
            page.mapTo(emails) { it.email }
            // 最後のページは満たない。ちょうど割り切れる場合は、次が空で返って終わる
            if (page.size < PAGE_SIZE) return emails
            offset += PAGE_SIZE
        }
        // **上限を置くのは、同じページが返り続けた日に日次バッチが終わらなくなるため**である。
        // 打ち切った事実を残さないと、全件を見たのか途中で止めたのかが読めない
        log.warn("stopped paging {} after {} pages", source.label, MAX_PAGES)
        return emails
    }

    private fun page(
        source: SuppressionSource,
        offset: Int,
    ): List<SuppressionEntry> =
        restClient
            .get()
            .uri { builder ->
                builder
                    .path(source.path)
                    .queryParam("limit", PAGE_SIZE)
                    .queryParam("offset", offset)
                    .build()
            }.retrieve()
            .body(ENTRY_LIST)
            ?: emptyList()

    internal companion object {
        val log = LoggerFactory.getLogger(SuppressionApiClient::class.java)

        /** 1 ページの件数。**API の上限が 500 である**（2026-08-29 に原文で確認）。 */
        const val PAGE_SIZE = 500

        /**
         * 辿るページ数の上限。**500 × 200 で 10 万件**に当たる。
         *
         * 月数万通規模（`docs/SPEC.md` §4.4）の抑制リストは、これに遠く届かない。
         * 置いてあるのは規模のためではなく、**終わらない日のため**である。
         */
        const val MAX_PAGES = 200

        val ENTRY_LIST = object : ParameterizedTypeReference<List<SuppressionEntry>>() {}
    }
}

/**
 * 応答の 1 件。**アドレスだけを拾う。**
 *
 * 4 つのリストで形が違う（`spam_reports` だけ `reason` を持たず `ip` を持つ）。
 * **共通する形へ寄せるより、要るものだけを取るほうが短い**——
 * 寄せると、持っていないフィールドを全部 null 許容で抱えることになる。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
internal data class SuppressionEntry(
    val email: String,
)
