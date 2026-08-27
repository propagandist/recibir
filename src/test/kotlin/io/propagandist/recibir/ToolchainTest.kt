package io.propagandist.recibir

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * ビルドが意図した JDK で走っていることを確かめる。
 *
 * この段階でアプリケーションコンテキストを立ち上げるテストは持たない。
 * Flyway が起動時にマイグレーションを実行するので、DB が無いと上がらないためである。
 * `@SpringBootTest` が意味を持つのは、Testcontainers を持ち込む作業順序 7 以降になる。
 */
class ToolchainTest {
    @Test
    fun `runs on JDK 25`() {
        assertEquals(25, Runtime.version().feature())
    }
}
