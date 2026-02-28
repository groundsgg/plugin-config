package gg.grounds.config

import gg.grounds.config.grpc.GrpcConfigClient
import gg.grounds.config.nats.NatsConfigListener
import gg.grounds.grpc.config.ConfigDefault
import gg.grounds.grpc.config.SyncDefaultsRequest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer
import org.slf4j.Logger
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonMapperBuilder

/**
 * Central manager for runtime configurations. Handles registration, sync, snapshot loading, and
 * live reload of typed config documents across multiple app/env scopes.
 *
 * Call [start] once to establish infrastructure connections (gRPC and NATS). Then call [register]
 * for each config definition, specifying which app and env it belongs to. Scopes are created lazily
 * — the first registration for a given (app, env) triggers default sync, initial snapshot load, and
 * a NATS subscription.
 */
class ConfigManager(private val logger: Logger) : AutoCloseable {
    private val scopes = ConcurrentHashMap<AppEnvKey, AppEnvScope>()
    private val definitionScopes = ConcurrentHashMap<ConfigDefinition<*>, AppEnvScope>()
    private val objectMapper: ObjectMapper =
        jacksonMapperBuilder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()
    private var grpcClient: GrpcConfigClient? = null
    private var natsListener: NatsConfigListener? = null
    private var started = false

    /**
     * Starts the config manager by connecting to the gRPC backend and the NATS server. Must be
     * called before [register].
     */
    fun start(grpcTarget: String, natsUrl: String) {
        val client = GrpcConfigClient.create(grpcTarget)
        grpcClient = client
        val listener = NatsConfigListener(logger)
        natsListener = listener
        listener.start(natsUrl)
        started = true
        logger.info("Config manager started (grpcTarget={}, natsUrl={})", grpcTarget, natsUrl)
    }

    /**
     * Registers a typed config definition for the given app and env. If this is the first
     * registration for that (app, env) pair, a new scope is created, defaults are synced, the
     * initial snapshot is loaded, and a NATS subscription is established.
     *
     * Each [ConfigDefinition] instance can only be registered once.
     */
    fun <T : Any> register(definition: ConfigDefinition<T>, app: String, env: String) {
        check(started) { "ConfigManager must be started before registering definitions" }
        if (definitionScopes.containsKey(definition)) {
            logger.warn(
                "Config definition already registered (namespace={}, key={})",
                definition.namespace,
                definition.key,
            )
            return
        }
        val scope = resolveScope(app, env)
        val configKey = ConfigKey(definition.namespace, definition.key)
        scope.register(configKey, ConfigBinding(definition))
        definitionScopes[definition] = scope
        logger.info(
            "Registered config definition (app={}, env={}, namespace={}, key={}, type={})",
            app,
            env,
            definition.namespace,
            definition.key,
            definition.type.simpleName,
        )
    }

    /** Returns the current typed value for the given config definition. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(definition: ConfigDefinition<T>): T {
        val scope =
            definitionScopes[definition] ?: throw ConfigDefinitionNotRegisteredException(definition)
        val configKey = ConfigKey(definition.namespace, definition.key)
        val binding = scope.binding(configKey) as? ConfigBinding<T>
        return binding?.get() ?: definition.defaultValue
    }

    /** Registers a callback that is invoked when the config value changes. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> onChange(definition: ConfigDefinition<T>, callback: Consumer<T>) {
        val scope =
            definitionScopes[definition] ?: throw ConfigDefinitionNotRegisteredException(definition)
        val configKey = ConfigKey(definition.namespace, definition.key)
        val binding = scope.binding(configKey) as? ConfigBinding<T>
        binding?.onChange(callback)
    }

    override fun close() {
        natsListener?.close()
        natsListener = null
        grpcClient?.close()
        grpcClient = null
        scopes.clear()
        definitionScopes.clear()
        started = false
        logger.info("Config manager closed")
    }

    private fun resolveScope(app: String, env: String): AppEnvScope {
        val key = AppEnvKey(app, env)
        return scopes.computeIfAbsent(key) { appEnvKey ->
            val scope = AppEnvScope(appEnvKey.app, appEnvKey.env)
            activateScope(scope)
            scope
        }
    }

    private fun activateScope(scope: AppEnvScope) {
        val client = grpcClient ?: return
        val listener = natsListener ?: return
        syncDefaults(client, scope)
        loadSnapshot(client, scope)
        listener.subscribe(scope.app, scope.env) { onNatsChangeReceived(scope) }
    }

    private fun syncDefaults(client: GrpcConfigClient, scope: AppEnvScope) {
        val bindings = scope.allBindings()
        if (bindings.isEmpty()) {
            return
        }
        try {
            val defaults =
                bindings.values.map { binding ->
                    val json = objectMapper.writeValueAsString(binding.definition.defaultValue)
                    ConfigDefault.newBuilder()
                        .setNamespace(binding.definition.namespace)
                        .setConfigKey(binding.definition.key)
                        .setDefaultContentJson(json)
                        .build()
                }
            val request =
                SyncDefaultsRequest.newBuilder()
                    .setApp(scope.app)
                    .setEnv(scope.env)
                    .addAllDefaults(defaults)
                    .build()
            val response = client.syncDefaults(request)
            if (response.createdKeysList.isNotEmpty()) {
                logger.info(
                    "Synced config defaults (app={}, env={}, created={})",
                    scope.app,
                    scope.env,
                    response.createdKeysList,
                )
            }
        } catch (error: Exception) {
            logger.error(
                "Failed to sync config defaults (app={}, env={}, error={})",
                scope.app,
                scope.env,
                error.message,
            )
        }
    }

    private fun loadSnapshot(client: GrpcConfigClient, scope: AppEnvScope) {
        try {
            val response = client.getSnapshot(scope.app, scope.env)
            applySnapshot(scope, response.version, response.documentsList)
        } catch (error: Exception) {
            logger.error(
                "Failed to load config snapshot (app={}, env={}, error={})",
                scope.app,
                scope.env,
                error.message,
            )
        }
    }

    private fun onNatsChangeReceived(scope: AppEnvScope) {
        val client = grpcClient ?: return
        try {
            val response = client.getSnapshotIfNewer(scope.app, scope.env, scope.version())
            if (response.changed) {
                applySnapshot(scope, response.version, response.documentsList)
            }
        } catch (error: Exception) {
            logger.error(
                "Failed to reload config after NATS event (app={}, env={}, error={})",
                scope.app,
                scope.env,
                error.message,
            )
        }
    }

    private fun applySnapshot(
        scope: AppEnvScope,
        version: Long,
        documents: List<gg.grounds.grpc.config.ConfigDocument>,
    ) {
        for (document in documents) {
            val configKey = ConfigKey(document.namespace, document.configKey)
            val binding = scope.binding(configKey) ?: continue
            applyDocument(binding, document.contentJson)
        }
        val previousVersion = scope.setVersion(version)
        logger.info(
            "Applied config snapshot (app={}, env={}, version={}, previousVersion={}, documents={})",
            scope.app,
            scope.env,
            version,
            previousVersion,
            documents.size,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> applyDocument(binding: ConfigBinding<T>, contentJson: String) {
        try {
            val value = objectMapper.readValue(contentJson, binding.definition.type)
            binding.update(value)
        } catch (error: Exception) {
            logger.warn(
                "Failed to deserialize config (namespace={}, key={}, type={}, error={})",
                binding.definition.namespace,
                binding.definition.key,
                binding.definition.type.simpleName,
                error.message,
            )
        }
    }

    /** Internal scope holding per-(app, env) state: version tracking and config bindings. */
    private class AppEnvScope(val app: String, val env: String) {
        private val currentVersion = AtomicLong(0)
        private val bindings = ConcurrentHashMap<ConfigKey, ConfigBinding<*>>()

        fun register(key: ConfigKey, binding: ConfigBinding<*>) {
            bindings[key] = binding
        }

        fun binding(key: ConfigKey): ConfigBinding<*>? = bindings[key]

        fun allBindings(): Map<ConfigKey, ConfigBinding<*>> = bindings

        fun version(): Long = currentVersion.get()

        fun setVersion(version: Long): Long = currentVersion.getAndSet(version)
    }
}
