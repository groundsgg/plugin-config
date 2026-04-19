package gg.grounds.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.slf4j.LoggerFactory

class ConfigManagerProviderTest {
    @Test
    fun `register fails when a different manager is already registered`() {
        val firstManager = ConfigManager(LoggerFactory.getLogger("ConfigManagerProviderTest.first"))
        val secondManager =
            ConfigManager(LoggerFactory.getLogger("ConfigManagerProviderTest.second"))

        ConfigManagerProvider.unregister(firstManager)
        ConfigManagerProvider.unregister(secondManager)

        try {
            ConfigManagerProvider.register(firstManager)
            assertEquals(firstManager, ConfigManagerProvider.get())
            assertFailsWith<IllegalStateException> { ConfigManagerProvider.register(secondManager) }
            assertEquals(firstManager, ConfigManagerProvider.get())
        } finally {
            ConfigManagerProvider.unregister(secondManager)
            ConfigManagerProvider.unregister(firstManager)
        }
    }

    @Test
    fun `register is idempotent for the same manager and unregister clears provider`() {
        val manager = ConfigManager(LoggerFactory.getLogger("ConfigManagerProviderTest.single"))
        ConfigManagerProvider.unregister(manager)

        try {
            ConfigManagerProvider.register(manager)
            ConfigManagerProvider.register(manager)
            assertEquals(manager, ConfigManagerProvider.get())

            ConfigManagerProvider.unregister(manager)
            assertNull(ConfigManagerProvider.get())
        } finally {
            ConfigManagerProvider.unregister(manager)
        }
    }
}
