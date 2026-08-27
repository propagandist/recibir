package io.propagandist.recibir

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/** recibir のエントリポイント。 */
@SpringBootApplication
class RecibirApplication

fun main(args: Array<String>) {
    runApplication<RecibirApplication>(*args)
}
