package gg.grounds.config.internal.scope

import gg.grounds.config.AppEnvKey
import gg.grounds.config.ConfigDefinition
import gg.grounds.config.ConfigKey
import gg.grounds.config.internal.binding.ConfigBinding
import java.util.concurrent.ConcurrentHashMap

/** Internal registry for scope and definition lookups. */
internal class ConfigScopeRegistry {
    private val scopes = ConcurrentHashMap<AppEnvKey, AppEnvScope>()
    private val definitionScopes = ConcurrentHashMap<ConfigDefinition<*>, AppEnvScope>()

    fun resolveScope(app: String, env: String): AppEnvScope {
        val key = AppEnvKey(app, env)
        return scopes.computeIfAbsent(key) { appEnvKey ->
            AppEnvScope(appEnvKey.app, appEnvKey.env)
        }
    }

    fun <T : Any> register(
        definition: ConfigDefinition<T>,
        scope: AppEnvScope,
    ): RegistrationResult<T> {
        val existingScope = definitionScopes.putIfAbsent(definition, scope)
        if (existingScope != null) {
            return RegistrationResult.DefinitionAlreadyRegistered
        }

        val configKey = ConfigKey(definition.namespace, definition.key)
        val binding = ConfigBinding(definition)
        val existingBinding = scope.putBindingIfAbsent(configKey, binding)
        if (existingBinding != null) {
            definitionScopes.remove(definition, scope)
            return RegistrationResult.ConfigKeyAlreadyRegistered(configKey)
        }

        return RegistrationResult.Registered(configKey, binding)
    }

    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> get(definition: ConfigDefinition<T>): ConfigBinding<T>? {
        val scope = definitionScopes[definition] ?: return null
        val configKey = ConfigKey(definition.namespace, definition.key)
        return scope.binding(configKey) as? ConfigBinding<T>
    }

    fun <T : Any> unregister(
        definition: ConfigDefinition<T>,
        scope: AppEnvScope,
        binding: ConfigBinding<T>,
    ) {
        val configKey = ConfigKey(definition.namespace, definition.key)
        scope.removeBinding(configKey, binding)
        definitionScopes.remove(definition, scope)
    }

    fun clear() {
        scopes.clear()
        definitionScopes.clear()
    }

    sealed interface RegistrationResult<out T : Any> {
        data class Registered<T : Any>(val configKey: ConfigKey, val binding: ConfigBinding<T>) :
            RegistrationResult<T>

        data object DefinitionAlreadyRegistered : RegistrationResult<Nothing>

        data class ConfigKeyAlreadyRegistered(val configKey: ConfigKey) :
            RegistrationResult<Nothing>
    }
}
