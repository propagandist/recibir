package io.propagandist.recibir.reconcile

/**
 * 突き合わせる Suppression API のリスト（`docs/SPEC.md` §4.5）。
 *
 * **並び順が補完の優先度である。** 同じアドレスが 2 つのリストに載っていることはあり、
 * 先に当たったものの [reasonCode] が残る。順は `docs/SPEC.md` §4.4 の状態決定に合わせてある
 * ——`spamreport` が `bounce` より強い。
 *
 * 4 つとも `limit`（最大 500）／ `offset` ／ `start_time` ／ `end_time` ／ `email` を受け、
 * 応答は配列である（2026-08-29 に原文で確認）。**共通するのは `created` と `email` だけ**で、
 * `spam_reports` は `reason` を持たず `ip` を持つ。
 *
 * @property path `https://api.sendgrid.com` からのパス
 * @property reasonCode 補完するときに書く値。**`null` は「状態を書き換えない」を意味する**
 *   ——[SuppressionReconciler] の KDoc にその理由がある
 */
enum class SuppressionSource(
    val path: String,
    val reasonCode: String?,
) {
    SPAM_REPORTS("/v3/suppression/spam_reports", "spam_report"),
    BOUNCES("/v3/suppression/bounces", "hard_bounce"),

    /** `invalid` を付けられるのはここだけである（`docs/SPEC.md` §3.2 の 4 つ目）。 */
    INVALID_EMAILS("/v3/suppression/invalid_emails", "invalid"),

    /** **一時的な失敗なので状態を書き換えない**（[SuppressionReconciler] の KDoc）。 */
    BLOCKS("/v3/suppression/blocks", null),
    ;

    /**
     * ログの `source` に出す名前。
     *
     * **パスの末尾から取る。** 別に定数を置くと、パスと名前が食い違う形ができる。
     */
    val label: String get() = path.substringAfterLast('/')
}
