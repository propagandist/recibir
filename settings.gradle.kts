// settings 側のプラグインも解決済みグラフに残す（settings-gradle.lockfile に出る）。
buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
}

// toolchain の JDK をどこから取るかを宣言する。
// これが無いと Gradle は自動取得した JDK を黙って使い、Gradle 10 ではエラーになる
// （9.7.1 は「他の環境ではビルドが失敗しうる」と警告する）。
//
// ビルド時に外部からバイナリが入ってくることになる。それを承知で採ったのは、
// このリポジトリが読まれることを目的にしているため——読者が JDK 25 を先に
// 用意しなくても ./gradlew build が通るほうを優先した。
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "recibir"
