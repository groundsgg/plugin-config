package gg.grounds.config

import gg.grounds.config.internal.scope.ConfigScopeRegistry
import gg.grounds.config.internal.sync.ConfigScopeSynchronizer
import java.util.function.Consumer
import org.slf4j.Logger

/**
 * Central manager for runtime configurations. Handles registration, sync, snapshot loading, and
 * live reload of typed config documents across multiple app/env scopes.
 *
 * Call [start] once to establish infrastructure connections (gRPC and NATS). Then call [register]
 * for each config definition, specifying which app and env it belongs to.
 */
class ConfigManager(private val logger: Logger) : AutoCloseable {
    private val scopeRegistry = ConfigScopeRegistry()
    private val scopeSynchronizer = ConfigScopeSynchronizer(logger)

    @Volatile private var started = false

    /**
     * Starts the config manager by connecting to the gRPC backend and the NATS server. Must be
     * called before [register].
     */
    fun start(grpcTarget: String, natsUrl: String) {
        if (started) {
            logger.warn(
                "Config manager start skipped (reason=already_started, grpcTarget={}, natsUrl={})",
                grpcTarget,
                natsUrl,
            )
            return
        }
        scopeSynchronizer.start(grpcTarget, natsUrl)
        started = true
        logger.info("Config manager started (grpcTarget={}, natsUrl={})", grpcTarget, natsUrl)
    }

    /**
     * Registers a typed config definition for the given app and env.
     *
     * Each [ConfigDefinition] instance can only be registered once.
     */
    fun <T : Any> register(definition: ConfigDefinition<T>, app: String, env: String) {
        check(started) { "ConfigManager must be started before registering definitions" }

        val scope = scopeRegistry.resolveScope(app, env)
        when (val result = scopeRegistry.register(definition, scope)) {
            is ConfigScopeRegistry.RegistrationResult.DefinitionAlreadyRegistered -> {
                logger.warn(
                    "Config definition registration skipped (reason=definition_already_registered, namespace={}, key={})",
                    definition.namespace,
                    definition.key,
                )
                return
            }
            is ConfigScopeRegistry.RegistrationResult.ConfigKeyAlreadyRegistered -> {
                logger.warn(
                    "Config definition registration failed (reason=config_key_already_registered, app={}, env={}, namespace={}, key={})",
                    app,
                    env,
                    result.configKey.namespace,
                    result.configKey.configKey,
                )
                return
            }
            is ConfigScopeRegistry.RegistrationResult.Registered -> {
                scopeSynchronizer.bootstrap(scope, result.binding)
            }
        }

        logger.info(
            "Config definition registered (app={}, env={}, namespace={}, key={}, type={})",
            app,
            env,
            definition.namespace,
            definition.key,
            definition.type.simpleName,
        )
    }

    /** Returns the current typed value for the given config definition. */
    fun <T : Any> get(definition: ConfigDefinition<T>): T {
        val binding =
            scopeRegistry.binding(definition)
                ?: throw ConfigDefinitionNotRegisteredException(definition)
        if (!binding.initialized()) {
            throw ConfigDefinitionNotReadyException(definition)
        }
        return binding.get()
    }

    /** Registers a callback that is invoked when the config value changes. */
    fun <T : Any> onChange(definition: ConfigDefinition<T>, callback: Consumer<T>) {
        val binding =
            scopeRegistry.binding(definition)
                ?: throw ConfigDefinitionNotRegisteredException(definition)
        binding.onChange(callback)
    }

    override fun close() {
        scopeSynchronizer.close()
        scopeRegistry.clear()
        started = false
        logger.info("Config manager closed")
    }
}
