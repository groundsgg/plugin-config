package gg.grounds.config.internal.sync

import gg.grounds.config.ConfigKey
import gg.grounds.config.ConfigRegistrationException
import gg.grounds.config.ConfigRegistrationResult
import gg.grounds.config.ConfigSnapshot
import gg.grounds.config.ConfigStartupMode
import gg.grounds.config.grpc.ConfigSyncClient
import gg.grounds.config.grpc.GrpcConfigClient
import gg.grounds.config.internal.binding.ConfigBinding
import gg.grounds.config.internal.cache.ConfigSnapshotCache
import gg.grounds.config.internal.scope.AppEnvScope
import gg.grounds.config.nats.ConfigChangeListener
import gg.grounds.config.nats.NatsConfigListener
import gg.grounds.grpc.config.ConfigDefault
import gg.grounds.grpc.config.ConfigDocument
import gg.grounds.grpc.config.SyncDefaultsRequest
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.slf4j.Logger
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonMapperBuilder

/** Internal coordinator for syncing defaults and applying snapshots to scope bindings. */
internal class ConfigScopeSynchronizer(
    private val logger: Logger,
    private val grpcClientFactory: (String) -> ConfigSyncClient = { target ->
        GrpcConfigClient.create(target)
    },
    private val natsListenerFactory: (Logger) -> ConfigChangeListener = { syncLogger ->
        NatsConfigListener(syncLogger)
    },
    private val refreshExecutorFactory: () -> ScheduledExecutorService = {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "config-scope-refresh").apply { isDaemon = true }
        }
    },
    private val sleepMillis: (Long) -> Unit = { delayMs -> Thread.sleep(delayMs) },
) : AutoCloseable {
    private val objectMapper: ObjectMapper =
        jacksonMapperBuilder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()
    private val trackedScopes = ConcurrentHashMap.newKeySet<AppEnvScope>()
    private var refreshExecutor: ScheduledExecutorService? = null
    private var grpcClient: ConfigSyncClient? = null
    private var natsListener: ConfigChangeListener? = null
    private var refreshFuture: ScheduledFuture<*>? = null
    private var snapshotCache: ConfigSnapshotCache = ConfigSnapshotCache.noop()

    @Synchronized
    fun start(grpcTarget: String, natsUrl: String, cacheDirectory: Path? = null) {
        stopRuntime()
        val client = grpcClientFactory(grpcTarget)
        val listener = natsListenerFactory(logger)
        val executor = ensureRefreshExecutor()
        listener.start(natsUrl)
        grpcClient = client
        natsListener = listener
        snapshotCache = ConfigSnapshotCache.create(logger, cacheDirectory)
        refreshFuture =
            executor.scheduleWithFixedDelay(
                { refreshTrackedScopes() },
                REFRESH_INTERVAL_SECONDS,
                REFRESH_INTERVAL_SECONDS,
                TimeUnit.SECONDS,
            )
    }

    fun bootstrap(
        scope: AppEnvScope,
        binding: ConfigBinding<*>,
        startupMode: ConfigStartupMode,
    ): ConfigRegistrationResult {
        val client =
            checkNotNull(grpcClient) {
                "Config scope bootstrap failed (reason=grpc_client_not_started)"
            }
        val listener =
            checkNotNull(natsListener) {
                "Config scope bootstrap failed (reason=nats_listener_not_started)"
            }
        trackedScopes.add(scope)
        var bootstrapFailure: Exception? = null
        var registrationResult = ConfigRegistrationResult.ready(scope.version())
        scope.withRefreshLock {
            bootstrapFailure =
                try {
                    syncDefault(client, scope, binding)
                    null
                } catch (error: Exception) {
                    error
                }
            subscribeToChanges(listener, scope)
            if (bootstrapFailure == null) {
                bootstrapFailure =
                    try {
                        refreshScope(client, scope, forceFullSnapshot = true)
                        null
                    } catch (error: Exception) {
                        error
                    }
            }
            if (bootstrapFailure != null) {
                registrationResult =
                    handleBootstrapFailure(scope, binding, startupMode, bootstrapFailure!!)
            } else {
                registrationResult =
                    if (binding.initialized()) {
                        ConfigRegistrationResult.ready(scope.version())
                    } else {
                        handleUninitializedBinding(scope, binding, startupMode)
                    }
            }
        }
        return registrationResult
    }

    @Synchronized
    override fun close() {
        stopRuntime()
        refreshExecutor?.shutdownNow()
        refreshExecutor = null
    }

    private fun ensureRefreshExecutor(): ScheduledExecutorService {
        val executor = refreshExecutor
        if (executor != null && !executor.isShutdown && !executor.isTerminated) {
            return executor
        }
        return refreshExecutorFactory().also { createdExecutor ->
            refreshExecutor = createdExecutor
        }
    }

    private fun stopRuntime() {
        refreshFuture?.cancel(false)
        refreshFuture = null
        trackedScopes.clear()
        natsListener?.close()
        natsListener = null
        grpcClient?.close()
        grpcClient = null
        snapshotCache = ConfigSnapshotCache.noop()
    }

    private fun subscribeToChanges(listener: ConfigChangeListener, scope: AppEnvScope) {
        if (!scope.markSubscriptionStarted()) {
            return
        }
        listener.subscribe(scope.app, scope.env) { onNatsChangeReceived(scope) }
    }

    private fun onNatsChangeReceived(scope: AppEnvScope) {
        val client = grpcClient ?: return
        scope.withRefreshLock {
            try {
                refreshScope(client, scope, forceFullSnapshot = false)
            } catch (error: Exception) {
                logger.error(
                    "Config reload failed (app={}, env={}, reason=nats_refresh_failed)",
                    scope.app,
                    scope.env,
                    error,
                )
            }
        }
    }

    private fun syncDefault(
        client: ConfigSyncClient,
        scope: AppEnvScope,
        binding: ConfigBinding<*>,
    ) {
        val json = objectMapper.writeValueAsString(binding.definition.defaultValue)
        val default =
            ConfigDefault.newBuilder()
                .setNamespace(binding.definition.namespace)
                .setConfigKey(binding.definition.key)
                .setDefaultContentJson(json)
                .build()
        val request =
            SyncDefaultsRequest.newBuilder()
                .setApp(scope.app)
                .setEnv(scope.env)
                .addDefaults(default)
                .build()
        val response =
            executeGrpcCall(
                scope = scope,
                operation = "sync_defaults",
                maxAttempts = BOOTSTRAP_GRPC_MAX_ATTEMPTS,
            ) {
                client.syncDefaults(request)
            }
        if (response.createdKeysList.isNotEmpty()) {
            val createdKeys =
                response.createdKeysList.map { createdKey ->
                    "${createdKey.namespace}/${createdKey.configKey}"
                }
            logger.info(
                "Config default synced successfully (app={}, env={}, createdKeys={})",
                scope.app,
                scope.env,
                createdKeys,
            )
        }
    }

    private fun refreshTrackedScopes() {
        val client = grpcClient ?: return
        for (scope in trackedScopes) {
            scope.withRefreshLock {
                try {
                    refreshScope(client, scope, forceFullSnapshot = false)
                } catch (error: Exception) {
                    logger.warn(
                        "Config scope refresh failed (app={}, env={}, reason=periodic_refresh_failed)",
                        scope.app,
                        scope.env,
                        error,
                    )
                }
            }
        }
    }

    private fun refreshScope(
        client: ConfigSyncClient,
        scope: AppEnvScope,
        forceFullSnapshot: Boolean,
    ) {
        val requiresFullSnapshot = forceFullSnapshot || scope.hasUninitializedBindings()
        val response =
            if (requiresFullSnapshot) {
                executeGrpcCall(
                    scope = scope,
                    operation = "get_snapshot",
                    maxAttempts = BOOTSTRAP_GRPC_MAX_ATTEMPTS,
                ) {
                    client.getSnapshot(scope.app, scope.env)
                }
            } else {
                executeGrpcCall(
                    scope = scope,
                    operation = "get_snapshot_if_newer",
                    maxAttempts = REFRESH_GRPC_MAX_ATTEMPTS,
                ) {
                    client.getSnapshotIfNewer(scope.app, scope.env, scope.version())
                }
            }
        if (response.changed) {
            applySnapshot(scope, response.version, response.documentsList)
        }
    }

    private fun applySnapshot(scope: AppEnvScope, version: Long, documents: List<ConfigDocument>) {
        val documentsByKey =
            documents.associateBy { document -> ConfigKey(document.namespace, document.configKey) }
        val bindings = scope.bindingsSnapshot()
        for ((configKey, binding) in bindings) {
            val document = documentsByKey[configKey]
            if (document != null) {
                applyDocument(binding, document.contentJson)
            } else {
                handleMissingDocument(scope, binding)
            }
        }
        val previousVersion = scope.setVersion(version)
        snapshotCache.save(
            scope.app,
            scope.env,
            ConfigSnapshot(version, buildCachedDocuments(bindings, documentsByKey)),
        )
        logger.info(
            "Config snapshot applied (app={}, env={}, version={}, previousVersion={}, documents={})",
            scope.app,
            scope.env,
            version,
            previousVersion,
            documents.size,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyDocument(binding: ConfigBinding<*>, contentJson: String) {
        try {
            val typedBinding = binding as ConfigBinding<Any>
            val value = objectMapper.readValue(contentJson, typedBinding.definition.type)
            typedBinding.update(value)
        } catch (error: Exception) {
            logger.warn(
                "Config deserialize failed (namespace={}, key={}, type={})",
                binding.definition.namespace,
                binding.definition.key,
                binding.definition.type.simpleName,
                error,
            )
        }
    }

    private fun buildCachedDocuments(
        bindings: Map<ConfigKey, ConfigBinding<*>>,
        documentsByKey: Map<ConfigKey, ConfigDocument>,
    ): Map<ConfigKey, String> {
        val cachedDocuments = documentsByKey.mapValuesTo(linkedMapOf()) { it.value.contentJson }
        for ((configKey, binding) in bindings) {
            if (binding.initialized()) {
                cachedDocuments[configKey] = objectMapper.writeValueAsString(binding.get())
            } else {
                cachedDocuments.remove(configKey)
            }
        }
        return cachedDocuments
    }

    private fun handleMissingDocument(scope: AppEnvScope, binding: ConfigBinding<*>) {
        if (binding.initialized()) {
            logger.warn(
                "Config document missing from snapshot (app={}, env={}, namespace={}, key={}, reason=missing_document, action=kept_previous_value)",
                scope.app,
                scope.env,
                binding.definition.namespace,
                binding.definition.key,
            )
            return
        }
        logger.error(
            "Config document missing from snapshot (app={}, env={}, namespace={}, key={}, reason=missing_document, action=left_uninitialized)",
            scope.app,
            scope.env,
            binding.definition.namespace,
            binding.definition.key,
        )
    }

    private fun handleBootstrapFailure(
        scope: AppEnvScope,
        binding: ConfigBinding<*>,
        startupMode: ConfigStartupMode,
        error: Exception,
    ): ConfigRegistrationResult {
        logger.error(
            "Config bootstrap failed (app={}, env={}, namespace={}, key={}, startupMode={}, reason=bootstrap_failed)",
            scope.app,
            scope.env,
            binding.definition.namespace,
            binding.definition.key,
            startupMode,
            error,
        )
        if (startupMode == ConfigStartupMode.DEGRADED) {
            val cachedSnapshot = snapshotCache.load(scope.app, scope.env)
            if (cachedSnapshot != null) {
                applyCachedSnapshot(scope, cachedSnapshot)
                if (binding.initialized()) {
                    logger.warn(
                        "Config bootstrap degraded successfully (app={}, env={}, namespace={}, key={}, version={}, reason=loaded_cached_snapshot)",
                        scope.app,
                        scope.env,
                        binding.definition.namespace,
                        binding.definition.key,
                        cachedSnapshot.version,
                    )
                    return ConfigRegistrationResult.degraded(
                        version = cachedSnapshot.version,
                        reason = "loaded_cached_snapshot",
                    )
                }
            }
            logger.warn(
                "Config bootstrap degraded without snapshot (app={}, env={}, namespace={}, key={}, reason=cache_unavailable)",
                scope.app,
                scope.env,
                binding.definition.namespace,
                binding.definition.key,
            )
            return ConfigRegistrationResult.notReady("bootstrap_failed_no_cached_snapshot")
        }
        throw ConfigRegistrationException(
            definition = binding.definition,
            app = scope.app,
            env = scope.env,
            message =
                "Config bootstrap failed (app=${scope.app}, env=${scope.env}, namespace=${binding.definition.namespace}, key=${binding.definition.key})",
            cause = error,
        )
    }

    private fun handleUninitializedBinding(
        scope: AppEnvScope,
        binding: ConfigBinding<*>,
        startupMode: ConfigStartupMode,
    ): ConfigRegistrationResult {
        if (startupMode == ConfigStartupMode.FAIL_CLOSED) {
            throw ConfigRegistrationException(
                definition = binding.definition,
                app = scope.app,
                env = scope.env,
                message =
                    "Config bootstrap left binding uninitialized (app=${scope.app}, env=${scope.env}, namespace=${binding.definition.namespace}, key=${binding.definition.key})",
            )
        }
        logger.warn(
            "Config bootstrap completed without ready value (app={}, env={}, namespace={}, key={}, startupMode={}, reason=binding_not_initialized)",
            scope.app,
            scope.env,
            binding.definition.namespace,
            binding.definition.key,
            startupMode,
        )
        return ConfigRegistrationResult.notReady("binding_not_initialized")
    }

    private fun applyCachedSnapshot(scope: AppEnvScope, snapshot: ConfigSnapshot) {
        val documents =
            snapshot.documents.map { (configKey, contentJson) ->
                ConfigDocument.newBuilder()
                    .setNamespace(configKey.namespace)
                    .setConfigKey(configKey.configKey)
                    .setContentJson(contentJson)
                    .build()
            }
        applySnapshot(scope, snapshot.version, documents)
    }

    private fun <T> executeGrpcCall(
        scope: AppEnvScope,
        operation: String,
        maxAttempts: Int,
        block: () -> T,
    ): T {
        var attempt = 1
        var retryDelayMs = INITIAL_GRPC_RETRY_DELAY_MS
        while (true) {
            try {
                return block()
            } catch (error: Exception) {
                if (!isRetryableGrpcFailure(error) || attempt >= maxAttempts) {
                    throw error
                }
                logger.warn(
                    "Config gRPC call failed (app={}, env={}, operation={}, attempt={}, maxAttempts={}, retryInMs={}, reason={})",
                    scope.app,
                    scope.env,
                    operation,
                    attempt,
                    maxAttempts,
                    retryDelayMs,
                    error.message ?: error::class.java.simpleName,
                )
                try {
                    sleepMillis(retryDelayMs)
                } catch (interruptedError: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw error
                }
                attempt += 1
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_GRPC_RETRY_DELAY_MS)
            }
        }
    }

    private fun isRetryableGrpcFailure(error: Exception): Boolean {
        return error is StatusException || error is StatusRuntimeException
    }

    companion object {
        private const val REFRESH_INTERVAL_SECONDS = 15L
        private const val INITIAL_GRPC_RETRY_DELAY_MS = 250L
        private const val MAX_GRPC_RETRY_DELAY_MS = 1000L
        private const val BOOTSTRAP_GRPC_MAX_ATTEMPTS = 3
        private const val REFRESH_GRPC_MAX_ATTEMPTS = 2
    }
}
