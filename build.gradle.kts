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
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    // Boot 4 は Jackson 3 が標準で、パッケージが tools.jackson.* に変わっている
    implementation("tools.jackson.module:jackson-module-kotlin")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jooq-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
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
