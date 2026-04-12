package gg.grounds.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.slf4j.LoggerFactory

class ConfigManagerProviderTest {
    @Test
    fun `unregister restores previous manager`() {
        val firstManager = ConfigManager(LoggerFactory.getLogger("ConfigManagerProviderTest.first"))
        val secondManager =
            ConfigManager(LoggerFactory.getLogger("ConfigManagerProviderTest.second"))

        ConfigManagerProvider.unregister(firstManager)
        ConfigManagerProvider.unregister(secondManager)

        try {
            ConfigManagerProvider.register(firstManager)
            ConfigManagerProvider.register(secondManager)

            assertEquals(secondManager, ConfigManagerProvider.get())

            ConfigManagerProvider.unregister(secondManager)

            assertEquals(firstManager, ConfigManagerProvider.get())

            ConfigManagerProvider.unregister(firstManager)

            assertNull(ConfigManagerProvider.get())
        } finally {
            ConfigManagerProvider.unregister(secondManager)
            ConfigManagerProvider.unregister(firstManager)
        }
    }
}
