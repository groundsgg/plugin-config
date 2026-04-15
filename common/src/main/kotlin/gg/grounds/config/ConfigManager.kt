package gg.grounds.config

import gg.grounds.config.internal.scope.ConfigScopeRegistry
import gg.grounds.config.internal.sync.ConfigScopeSynchronizer
import java.nio.file.Path
import java.util.function.Consumer
import org.slf4j.Logger

/**
 * Central manager for runtime configurations. Handles registration, sync, snapshot loading, and
 * live reload of typed config documents across multiple app/env scopes.
 *
 * Call [start] once to establish infrastructure connections (gRPC and NATS). Then call [register]
 * for each config definition, specifying which app and env it belongs to.
 */
class ConfigManager
private constructor(
    private val logger: Logger,
    private val scopeRegistry: ConfigScopeRegistry,
    private val scopeSynchronizer: ConfigScopeSynchronizer,
) : AutoCloseable {
    constructor(
        logger: Logger
    ) : this(logger, ConfigScopeRegistry(), ConfigScopeSynchronizer(logger))

    internal constructor(
        logger: Logger,
        scopeSynchronizerFactory: (Logger) -> ConfigScopeSynchronizer,
    ) : this(logger, ConfigScopeRegistry(), scopeSynchronizerFactory(logger))

    @Volatile private var started = false

    /**
     * Starts the config manager by connecting to the gRPC backend and the NATS server. Must be
     * called before [register].
     */
    fun start(grpcTarget: String, natsUrl: String, cacheDirectory: Path? = null) {
        if (started) {
            logger.warn(
                "Config manager start skipped (reason=already_started, grpcTarget={}, natsUrl={}, cacheDirectory={})",
                grpcTarget,
                natsUrl,
                cacheDirectory,
            )
            return
        }
        scopeSynchronizer.start(grpcTarget, natsUrl, cacheDirectory)
        started = true
        logger.info(
            "Config manager started (grpcTarget={}, natsUrl={}, cacheDirectory={})",
            grpcTarget,
            natsUrl,
            cacheDirectory,
        )
    }

    /**
     * Registers a typed config definition for the given app and env.
     *
     * Each [ConfigDefinition] instance can only be registered once.
     *
     * `startupMode` controls how bootstrap failures are handled:
     * - [ConfigStartupMode.FAIL_CLOSED] throws [ConfigRegistrationException] when default sync,
     *   snapshot loading, or binding initialization cannot produce a ready value.
     * - [ConfigStartupMode.DEGRADED] attempts to restore a persisted cached snapshot and returns
     *   [ConfigRegistrationStatus.DEGRADED] when that succeeds.
     *
     * The returned [ConfigRegistrationResult] should be checked by callers that allow degraded
     * startup so they can surface `DEGRADED` or `NOT_READY` explicitly during plugin startup.
     */
    fun <T : Any> register(
        definition: ConfigDefinition<T>,
        app: String,
        env: String,
        startupMode: ConfigStartupMode = ConfigStartupMode.FAIL_CLOSED,
    ): ConfigRegistrationResult {
        check(started) { "ConfigManager must be started before registering definitions" }

        val scope = scopeRegistry.resolveScope(app, env)
        when (val result = scopeRegistry.register(definition, scope)) {
            is ConfigScopeRegistry.RegistrationResult.DefinitionAlreadyRegistered -> {
                logger.warn(
                    "Config definition registration skipped (reason=definition_already_registered, app={}, env={}, namespace={}, key={})",
                    app,
                    env,
                    definition.namespace,
                    definition.key,
                )
                return ConfigRegistrationResult.alreadyRegistered("definition_already_registered")
            }
            is ConfigScopeRegistry.RegistrationResult.ConfigKeyAlreadyRegistered -> {
                logger.warn(
                    "Config definition registration failed (reason=config_key_already_registered, app={}, env={}, namespace={}, key={})",
                    app,
                    env,
                    result.configKey.namespace,
                    result.configKey.configKey,
                )
                return ConfigRegistrationResult.rejected("config_key_already_registered")
            }
            is ConfigScopeRegistry.RegistrationResult.Registered -> {
                val registrationResult =
                    try {
                        scopeSynchronizer.bootstrap(scope, result.binding, startupMode)
                    } catch (error: Exception) {
                        scopeRegistry.unregister(definition, scope, result.binding)
                        throw error
                    }
                logger.info(
                    "Config definition registered (app={}, env={}, namespace={}, key={}, type={}, status={}, reason={})",
                    app,
                    env,
                    definition.namespace,
                    definition.key,
                    definition.type.simpleName,
                    registrationResult.status,
                    registrationResult.reason,
                )
                return registrationResult
            }
        }
    }

    /** Returns the current typed value for the given config definition. */
    operator fun <T : Any> get(definition: ConfigDefinition<T>): T {
        val binding =
            scopeRegistry[definition] ?: throw ConfigDefinitionNotRegisteredException(definition)
        if (!binding.initialized()) {
            throw ConfigDefinitionNotReadyException(definition)
        }
        return binding.get()
    }

    /** Registers a callback that is invoked when the config value changes. */
    fun <T : Any> onChange(definition: ConfigDefinition<T>, callback: Consumer<T>) {
        val binding =
            scopeRegistry[definition] ?: throw ConfigDefinitionNotRegisteredException(definition)
        binding.onChange(callback)
    }

    override fun close() {
        scopeSynchronizer.close()
        scopeRegistry.clear()
        started = false
        logger.info("Config manager closed")
    }
}
