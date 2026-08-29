package io.propagandist.recibir.support

import org.springframework.boot.test.system.CapturedOutput
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * 出た構造化ログのうち、`event` が一致する行を**出た順に**取り出す。
 *
 * **stdout をそのまま読む。** 形式の定義は `config/CloudLoggingFormat` にあるが、
 * テストが見るのは**実際に出た 1 行**である——運用が読むのもこれになる。
 *
 * 受信経路（`webhook`）と射影（`event`）の 2 か所からログを見るようになった時点で、
 * `WebhookLoggingTest` からここへ出した。
 */
fun structuredLogs(
    output: CapturedOutput,
    event: String,
): List<JsonNode> =
    output.all
        .lineSequence()
        .filter { it.startsWith("{") }
        .map { mapper.readTree(it) }
        .filter { it["event"]?.stringValue() == event }
        .toList()

/**
 * 読むためだけのマッパー。
 *
 * **アプリの `JsonMapper` を注入していない。** ここが要るのは「出た 1 行を JSON として
 * 読み直す」ことだけで、アプリ側の設定に揃える理由が無い。揃えると、
 * **アプリの設定を変えた日にテストの読み方が黙って変わる。**
 */
private val mapper = JsonMapper.builder().build()
