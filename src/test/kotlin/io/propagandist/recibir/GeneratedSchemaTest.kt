package io.propagandist.recibir

import io.propagandist.jooq.Tables.SENDGRID_EVENT
import org.jooq.JSONB
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * jOOQ の生成コードが、Flyway のマイグレーションを反映していることを確かめる。
 *
 * DB へは接続しない。生成された定義を読むだけである。
 * ここが落ちるときは、スキーマを変えた後に `jooqCodegen` を打ち直していないか、
 * スキーマの入っていない DB に対して生成している（`docs/HANDOVER.md` の作業順序 4）。
 * 後者は生成物が空になるだけで、エラーにはならない。
 */
class GeneratedSchemaTest {
    @Test
    fun `sendgrid_event が生成されている`() {
        assertEquals("sendgrid_event", SENDGRID_EVENT.name)
    }

    @Test
    fun `payload は JSONB として生成されている`() {
        // 生 JSON をそのまま入れるカラムである（docs/er.md「正本は sendgrid_event」）。
        // ここが String になっていると、DTO へ射影した時点で型が崩れる。
        assertEquals(JSONB::class.java, SENDGRID_EVENT.PAYLOAD.type)
    }
}
