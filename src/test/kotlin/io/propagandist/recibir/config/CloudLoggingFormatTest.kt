package io.propagandist.recibir.config

import io.propagandist.recibir.support.PostgresContainer
import io.propagandist.recibir.support.registerSendGridProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 実際に起動したアプリが stdout へ出す 1 行を見る。
 *
 * **エンコーダを手で組み立てない。** それでは `application.yml` の
 * `logging.structured.format.console` が効いていることを確かめられず、
 * **設定を書き忘れても緑になる**——ログは間違っていても赤くならない種類のものである。
 *
 * `@SpringBootTest` なので DB が要る（`support/PostgresContainer`）。**Docker が要る。**
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension::class)
class CloudLoggingFormatTest {
    @Autowired
    private lateinit var jsonMapper: JsonMapper

    private val log = LoggerFactory.getLogger(CloudLoggingFormatTest::class.java)

    /**
     * このテストのロガーが出した最後の 1 行を JSON として読む。
     *
     * **パースが通ること自体が受け入れ基準の 1 つである**（#5「出力が有効な JSON であること」）。
     * 起動時のログが混ざるので、`logger` で自分の行に絞る。
     */
    private fun logged(output: CapturedOutput): JsonNode =
        output.all
            .lineSequence()
            .filter { it.startsWith("{") && it.contains(LOGGER_NAME) }
            .map { jsonMapper.readTree(it) }
            .last()

    @Test
    fun `warn は WARN ではなく WARNING で出る`(output: CapturedOutput) {
        // このクラスが存在する理由そのもの。LogSeverity に WARN は無い
        // （2026-08-28 確認。config/CloudLoggingFormat.kt の KDoc）
        log.warn("something to look at")

        assertEquals("WARNING", logged(output)["severity"].stringValue())
    }

    @Test
    fun `info はそのまま INFO で出る`(output: CapturedOutput) {
        log.info("nothing to see here")

        assertEquals("INFO", logged(output)["severity"].stringValue())
    }

    @Test
    fun `time は RFC 3339 として読める`(output: CapturedOutput) {
        // Cloud Logging が 3 番目に見る形。読めない文字列だと受信時刻に差し替わり、
        // 発生時刻との差が黙って消える
        log.info("stamped")

        val time = logged(output)["time"].stringValue()
        assertNotNull(Instant.parse(time), time)
    }

    @Test
    fun `message には整形済みの本文が入る`(output: CapturedOutput) {
        log.info("received {} events", 3)

        assertEquals("received 3 events", logged(output)["message"].stringValue())
    }

    @Test
    fun `addKeyValue はトップレベルの項目として出る`(output: CapturedOutput) {
        // 呼ぶ側はこの形でしか項目を足さない。入れ子になると
        // ログベース指標の参照先（jsonPayload.event）が変わる
        log
            .atInfo()
            .addKeyValue("event", "webhook.received")
            .addKeyValue("count", 3)
            .log("received")

        val entry = logged(output)
        assertEquals("webhook.received", entry["event"].stringValue())
        assertEquals(3, entry["count"].intValue())
    }

    @Test
    fun `例外が無いときは stack_trace を出さない`(output: CapturedOutput) {
        log.info("no throwable here")

        assertNull(logged(output)["stack_trace"])
    }

    @Test
    fun `例外があるときは stack_trace が出る`(output: CapturedOutput) {
        log.warn("failed", IllegalStateException("boom"))

        val trace = logged(output)["stack_trace"].stringValue()
        assertTrue(trace.contains("IllegalStateException"), trace)
    }

    companion object {
        private val LOGGER_NAME = CloudLoggingFormatTest::class.java.name

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            PostgresContainer.register(registry)
            registerSendGridProperties(registry)
        }
    }
}
