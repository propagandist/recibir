// プラグインの classpath は、下の dependencyLocking では捕まらない。
// ここで別に有効にしないと、Spring Boot と Kotlin のプラグインが引く推移依存が
// 解決済みグラフのどこにも残らない（buildscript-gradle.lockfile に出る）。
buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
    // Flyway の Gradle プラグインは、DB ごとのモジュールと JDBC ドライバを
    // 自分の classpath に見つけられないと動かない。Boot の BOM はここまで効かないので、
    // 版を手で書いて Boot が管理する版に合わせてある。
    dependencies {
        classpath("org.flywaydb:flyway-database-postgresql:12.4.0")
        classpath("org.postgresql:postgresql:42.7.13")
    }
}

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    // Boot の BOM が決めるのは Flyway ライブラリの版であって、Gradle プラグインの版ではない。
    // ずれると、起動時のマイグレーションと flywayMigrate が違う版の Flyway で動く。
    // Boot を上げたときは、この行も手で追随させること。
    id("org.flywaydb.flyway") version "12.4.0"
    // jOOQ 本体と同じところが出しているプラグインで、版も本体に追随する。
    // Boot の BOM が決める jOOQ ライブラリの版と同じ番号を書くこと。
    id("org.jooq.jooq-codegen-gradle") version "3.21.7"
}

group = "io.propagandist"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

// jOOQ の公式プラグインは、生成先をソースセットへ自動では足さない。
// これが無いと io.propagandist.jooq.* を import した Kotlin が解決できない。
//
// この行が入った時点で、clone しただけではビルドが通らなくなる——
// 生成コードを commit しない決定（CLAUDE.md）の裏返しである。
// 先に docker compose up -d → flywayMigrate → jooqCodegen が要る。
sourceSets {
    main {
        java.srcDir("src/generated")
    }
}

// ktlint 本体の版を固定する。プラグインが使う既定の版は
// プラグインの patch 版の間でも変わりうると README が書いており、
// 固定しないと検査の基準が黙って動く。
ktlint {
    version.set("1.8.0")
}

// jOOQ の codegen はスキーマの入った DB を要求する。その DB を用意する経路は
// compose.yaml に寄せた（Testcontainers はテストの側で使う）。
// 接続先は compose.yaml と同じ値で、application.yml とも揃えてある。
flyway {
    url = "jdbc:postgresql://localhost:15432/recibir"
    user = "recibir"
    password = "recibir"
}

// 生成先は src/generated で、.gitignore が除外している。
// 生成物を版管理に入れると、スキーマとの食い違いが「コミットし忘れ」として潜る。
// 接続先は flyway ブロックと同じ——先に flywayMigrate でスキーマを入れておかないと、
// ここは黙って空のコードを吐く。
jooq {
    configuration {
        jdbc {
            url = "jdbc:postgresql://localhost:15432/recibir"
            user = "recibir"
            password = "recibir"
        }
        generator {
            database {
                name = "org.jooq.meta.postgres.PostgresDatabase"
                inputSchema = "public"
                // Flyway の履歴テーブルはアプリが触らない。
                excludes = "flyway_schema_history"
            }
            target {
                packageName = "io.propagandist.jooq"
                directory = "src/generated"
            }
        }
    }
}

// 依存の解決済みグラフを commit するために有効にする。
// 版を宣言しただけでは、推移依存を含めて CVE を「見た」とは言えない。
// 及ぶのはこのプロジェクトの構成だけなので、プラグインの側は上で塞いである。
dependencyLocking {
    lockAllConfigurations()
}

// 版を書いてあるのは Spring Boot プラグインの行だけである。
// Kotlin・jOOQ・Flyway・PostgreSQL ドライバの版は Boot の BOM が決める。
// ここで上書きすると、Boot が整合を保証している組み合わせを崩すことになる。
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-security")
    // OAuth を有効にしたときの JWT 検証（docs/SPEC.md §4.6）。
    // 受信のたびに認可サーバへ問い合わせる introspection を選ばなかったのは、
    // 外部往復が §6「200 を返すまでを短く保つ」を崩すためである（#43）。
    // 名前は spring-boot-starter-oauth2-resource-server ではない。あちらも解決できるが、
    // pom の description が「deprecated in favor of ...-security-...」と書いている
    // （2026-08-30 実測）。starter-web → starter-webmvc と同じ改名である。
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    // Boot 4 は Jackson 3 が標準で、パッケージが tools.jackson.* に変わっている
    implementation("tools.jackson.module:jackson-module-kotlin")
    runtimeOnly("org.postgresql:postgresql")
    // codegen は DB へ実接続してスキーマを読む。専用の configuration が要る。
    jooqCodegen("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jooq-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    // DB を伴うテストは Testcontainers を使う（CLAUDE.md「テスト方針」）。
    // compose.yaml の DB は codegen 用で、用途が違う（docs/HANDOVER.md「詰まりやすいところ」）。
    // 版は Boot の BOM が持つ testcontainers-bom が決める。
    //
    // artifactId に testcontainers- が付く。Boot 4.1 の BOM が決めるのは 2.0.5 で、
    // 1.x の org.testcontainers:postgresql は 2.x に存在しない
    // （2026-08-28 実測。解決が FAILED になる）。
    //
    // spring-boot-testcontainers（@ServiceConnection）も junit-jupiter の拡張
    // （@Testcontainers / @Container）も入れない。コンテナはテストクラスをまたいで
    // 1 つだけ立てたいので自分で start() し（test/support/PostgresContainer.kt）、
    // 接続情報は @DynamicPropertySource で流す——公開鍵をテストへ渡す経路と
    // 同じ仕組みになり、読む人が覚えるものが 1 つで済む。
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        // Spring Framework 7 は JSpecify を採用し、JSR-305 ベースの
        // org.springframework.lang.* は非推奨になった。Spring Initializr は
        // いまも -Xjsr305=strict を出すが、それでは効き先が非推奨の側になる。
        // strict は Kotlin の既定値でもあるが、既定に頼ると
        // 「決めた」のか「知らなかった」のかを読んだ人が区別できない。
        //
        // -Xannotation-default-target は Initializr の出力のまま残してある。
        // 外すと data class のコンストラクタ引数へアノテーションが付かず、
        // @param:JsonProperty を自分で書く羽目になる。
        freeCompilerArgs.addAll(
            "-Xjspecify-annotations=strict",
            "-Xannotation-default-target=param-property",
        )
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
