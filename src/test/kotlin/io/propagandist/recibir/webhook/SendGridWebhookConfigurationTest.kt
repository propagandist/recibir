package io.propagandist.recibir.webhook

import io.propagandist.recibir.support.TestKeyPair
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 公開鍵の設定が無いときに、**起動が止まること**を確かめる。
 *
 * 落ちてほしい理由は `SendGridWebhookConfiguration` の KDoc にある——
 * **黙って起動されるほうが困る**。鍵が無いまま動くと全イベントが 403 になり、
 * それは「配信が全部成功している」と見分けが付かない。
 */
class SendGridWebhookConfigurationTest {
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration::class.java))
            .withUserConfiguration(SendGridWebhookConfiguration::class.java)

    @Test
    fun `公開鍵が設定されていないと起動に失敗する`() {
        runner.run { context -> assertNotNull(context.startupFailure) }
    }

    @Test
    fun `公開鍵があれば検証器が組み上がる`() {
        val keyPair = TestKeyPair.generate()
        runner
            .withPropertyValues("sendgrid.webhook.public-key=${TestKeyPair.publicKeyBase64(keyPair)}")
            .run { context ->
                assertNull(context.startupFailure)
                context.getBean(SendGridSignatureVerifier::class.java)
            }
    }
}
