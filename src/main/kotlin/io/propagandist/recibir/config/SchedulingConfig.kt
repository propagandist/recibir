package io.propagandist.recibir.config

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * `@Scheduled` を有効にする。
 *
 * 射影は `@Scheduled` のポーリングで動く（`docs/SPEC.md` §4.4）。
 * **月数万通規模ではメッセージキューを持ち込む理由が無い。**
 *
 * **Executor の設定を置いていない。** Boot の既定は 1 本のスレッドで、いま定期実行するのは
 * `EventProjector` だけである。日次の突き合わせ（同 §4.5）が入った日に、
 * 同時に走らせる必要が出たら決める——**先に用意すると、使われない設定だけが残る。**
 */
@Configuration
@EnableScheduling
class SchedulingConfig
