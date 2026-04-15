package gg.grounds.config.internal.scope

import gg.grounds.config.ConfigDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class ConfigScopeRegistryTest {
    @Test
    fun `register rejects duplicate definitions and keys`() {
        val registry = ConfigScopeRegistry()
        val scope = registry.resolveScope(app = "test-app", env = "dev")
        val otherScope = registry.resolveScope(app = "test-app", env = "prod")

        val firstRegistration = registry.register(TestConfig, scope)
        val duplicateDefinition = registry.register(TestConfig, otherScope)
        val duplicateKey = registry.register(AnotherConfigWithSameKey, scope)

        assertIs<ConfigScopeRegistry.RegistrationResult.Registered<*>>(firstRegistration)
        assertIs<ConfigScopeRegistry.RegistrationResult.DefinitionAlreadyRegistered>(
            duplicateDefinition
        )
        val duplicateKeyResult =
            assertIs<ConfigScopeRegistry.RegistrationResult.ConfigKeyAlreadyRegistered>(
                duplicateKey
            )
        assertEquals("plugin-config", duplicateKeyResult.configKey.namespace)
        assertEquals("message", duplicateKeyResult.configKey.configKey)
        assertNotNull(registry[TestConfig])
    }

    private object TestConfig :
        ConfigDefinition<String>(
            namespace = "plugin-config",
            key = "message",
            type = String::class.java,
            defaultValue = "default",
        )

    private object AnotherConfigWithSameKey :
        ConfigDefinition<String>(
            namespace = "plugin-config",
            key = "message",
            type = String::class.java,
            defaultValue = "other",
        )
}
