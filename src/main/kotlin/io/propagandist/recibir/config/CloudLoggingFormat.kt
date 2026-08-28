package io.propagandist.recibir.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.pattern.ThrowableProxyConverter
import ch.qos.logback.classic.spi.ILoggingEvent
import org.slf4j.event.KeyValuePair
import org.springframework.boot.json.JsonWriter
import org.springframework.boot.json.JsonWriter.PairExtractor
import org.springframework.boot.logging.structured.ContextPairs
import org.springframework.boot.logging.structured.JsonWriterStructuredLogFormatter
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer

/**
 * 構造化ログを 1 行の JSON として stdout へ出す形式。
 *
 * 設計の正本は #5 である。**アプリは外部へ送信しない**——出すのは stdout までで、
 * 転送も発報も運用側が持つ（判断は #1）。依存は 1 つも増やしていない。
 *
 * ## 組み込みの `ecs` を使わずに自分で書いた理由
 *
 * **`severity` の値である。** ECS が出す `log.level` は Logback の名前をそのまま使うので
 * `WARN` になるが、**Cloud Logging の `LogSeverity` に `WARN` という値は無い**
 * （正式な名称は `WARNING`。2026-08-28 に列挙を確認）。
 *
 * `logging.structured.json.rename` は**キー名しか変えられない**。値は変換できないので、
 * 組み込み形式のままでは `severity` を作れない。[severityOf] がその変換である。
 *
 * 自分で書いたことで、出力に何が出るかがこのファイルだけで読めるようにもなった。
 * ECS は `ecs.version` や `process.*` を含む広い形式で、ここで要るのは下の 6 つだけである。
 *
 * ## 時刻のキーが `time` である理由
 *
 * Cloud Logging は時刻を 3 つの形で探し、**`timestamp`（`seconds` と `nanos` を持つ
 * オブジェクト）→ `timestampSeconds` と `timestampNanos` の対 → `time`（RFC 3339 の文字列）**
 * の順に見る（2026-08-28 確認）。3 番目が最も素直に書ける形なので、これを採った。
 */
class CloudLoggingFormat(
    contextPairs: ContextPairs,
    throwableProxyConverter: ThrowableProxyConverter,
    customizerBuilder: StructuredLoggingJsonMembersCustomizer.Builder<*>,
) : JsonWriterStructuredLogFormatter<ILoggingEvent>(
        { members -> jsonMembers(contextPairs, throwableProxyConverter, members) },
        customizerBuilder.nested().build(),
    )

/**
 * `addKeyValue` が積んだ対を、キーと値に分けて取り出す。
 *
 * 組み込みの ECS 形式と同じ作りである（`ElasticCommonSchemaStructuredLogFormatter`）。
 */
private val keyValuePairExtractor: PairExtractor<KeyValuePair> =
    PairExtractor.of({ pair -> pair.key }, { pair -> pair.value })

/**
 * 1 行に出すものを並べる。**ここに無いものは出ない。**
 *
 * `event` や件数のような、ログごとに違う項目は下の `usingPairs` が拾う——
 * 呼ぶ側は SLF4J の `addKeyValue` を使い、この関数には手を入れない。
 */
private fun jsonMembers(
    contextPairs: ContextPairs,
    throwableProxyConverter: ThrowableProxyConverter,
    members: JsonWriter.Members<ILoggingEvent>,
) {
    // Instant がそのまま RFC 3339 の文字列になる（上の KDoc）
    members.add("time") { event: ILoggingEvent -> event.instant }
    members.add("severity") { event: ILoggingEvent -> severityOf(event.level) }
    members.add("message") { event: ILoggingEvent -> event.formattedMessage }
    members.add("logger") { event: ILoggingEvent -> event.loggerName }
    members.add("thread") { event: ILoggingEvent -> event.threadName }
    // addKeyValue と MDC を、入れ子にせずトップレベルへ並べる。
    // Cloud Monitoring のログベース指標は jsonPayload.<キー> で引くので、浅いほうが書きやすい
    members.add().usingPairs(
        contextPairs.nested { pairs ->
            pairs.addMapEntries { event: ILoggingEvent -> event.mdcPropertyMap }
            pairs.add({ event: ILoggingEvent -> event.keyValuePairs }, keyValuePairExtractor)
        },
    )
    // 例外が無いログのほうが多いので、あるときだけ出す
    members
        .add("stack_trace") { event: ILoggingEvent ->
            event.throwableProxy?.let { throwableProxyConverter.convert(event) }
        }.whenNotNull()
}

/**
 * Logback の水準を `LogSeverity` の名前へ写す。
 *
 * **`WARN` を `WARNING` にするのが、このクラスが存在する理由である**（上の KDoc）。
 * `TRACE` を落とす先が無いので `DEBUG` にまとめている——`LogSeverity` の下限が
 * `DEBUG` で、それより細かい区分を持たない。
 */
private fun severityOf(level: Level): String =
    when (level.toInt()) {
        Level.ERROR_INT -> "ERROR"
        Level.WARN_INT -> "WARNING"
        Level.INFO_INT -> "INFO"
        else -> "DEBUG"
    }
