package io.propagandist.recibir.support

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * DB を伴うテストが使う PostgreSQL。
 *
 * **`compose.yaml` の DB は使わない。** あちらは jOOQ の codegen 用で、用途が違う
 * （`docs/HANDOVER.md`「詰まりやすいところ」）。テストがそちらのスキーマを汚すと、
 * 次の `jooqCodegen` の入力が変わってしまう。
 *
 * **`object` にして 1 つだけ立てる。** テストクラスごとにコンテナを起動すると、
 * クラスが増えるたびに待ち時間が積み上がる。JVM が終わるときの片付けは
 * Testcontainers 側（Ryuk）が持つので、こちらで止める必要はない。
 *
 * 版は `compose.yaml` と揃えてある。**手元と CI とテストで別の版を使わない。**
 */
object PostgresContainer {
    private val instance =
        PostgreSQLContainer("postgres:16-alpine").apply { start() }

    /**
     * 接続情報を Spring の設定へ流し込む。
     *
     * 呼び出し側は `@DynamicPropertySource` の中でこれを呼ぶ。
     * **`application.yml` の `spring.datasource.*` を上書きする**ので、
     * テストが手元の PostgreSQL を触ることはない。
     */
    fun register(registry: DynamicPropertyRegistry) {
        registry.add("spring.datasource.url") { instance.jdbcUrl }
        registry.add("spring.datasource.username") { instance.username }
        registry.add("spring.datasource.password") { instance.password }
    }
}
