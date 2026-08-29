package io.propagandist.recibir.event

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * ログへ出すアドレスの伏せ方（`docs/SPEC.md` §4.7 / #5）。
 *
 * **DB も Spring も要らない。** 実際に出た 1 行の中身は `EventProjectorTest` が見る。
 */
class EmailMaskTest {
    @Test
    fun `ローカル部を伏せ、ドメインは残す`() {
        // ドメインを残すのは、特定ドメインへの集中的な配信不能をログだけで読むためである
        assertEquals("***@example.com", maskEmail("user@example.com"))
    }

    @Test
    fun `1 文字のローカル部も全部伏せる`() {
        // 先頭 1 文字を残す形だと、ここでローカル部がそのまま全部出る
        assertEquals("***@example.com", maskEmail("a@example.com"))
    }

    @Test
    fun `アットマークが無ければ全部伏せる`() {
        // email 列に何が入るかを決めているのは SendGrid であって、こちらではない
        assertEquals("***", maskEmail("not-an-address"))
    }

    @Test
    fun `空文字も全部伏せる`() {
        assertEquals("***", maskEmail(""))
    }

    @Test
    fun `アットマークが複数あれば最後で分ける`() {
        // ローカル部に @ を含む形（引用符で囲んだもの）が来ても、残るのはドメインだけになる
        assertEquals("***@example.com", maskEmail("a@b@example.com"))
    }
}
