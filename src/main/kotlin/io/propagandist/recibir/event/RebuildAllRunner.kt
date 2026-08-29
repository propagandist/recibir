package io.propagandist.recibir.event

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * `--rebuild-all` が付いていたら、起動時に全件を作り直す（`docs/SPEC.md` §4.4）。
 *
 * 解釈を直した日のためにある。`email_address_state` は捨てて作り直せる二次テーブルで、
 * 作り直す手段が無ければ、直したロジックが過去のイベントに届かない。
 *
 * **HTTP のエンドポイントにしていない。** 管理 UI に近づき、同 §2 の「含まない」に触れる。
 * **設定項目にもしていない**（同 §5）——コマンドライン引数は設定ではなく、
 * **その 1 回の起動の指示**である。
 *
 * **作り直した後も、そのまま起動を続ける。** 終了させる形にすると、起動コマンドへ
 * 引数が残った日にアプリが二度と上がらなくなる。続ければ、残っていても
 * 起動が少し遅くなるだけで済む。
 */
@Component
class RebuildAllRunner(
    private val projector: EventProjector,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        if (!args.containsOption(OPTION)) return
        projector.rebuildAll()
    }

    private companion object {
        /** `--rebuild-all`。**接頭辞の `--` は [ApplicationArguments] 側が外して持つ。** */
        const val OPTION = "rebuild-all"
    }
}
