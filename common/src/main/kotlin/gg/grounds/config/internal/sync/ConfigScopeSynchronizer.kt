package gg.grounds.config.internal.sync

import gg.grounds.config.ConfigKey
import gg.grounds.config.grpc.GrpcConfigClient
import gg.grounds.config.internal.binding.ConfigBinding
import gg.grounds.config.internal.scope.AppEnvScope
import gg.grounds.config.nats.NatsConfigListener
import gg.grounds.grpc.config.ConfigDefault
import gg.grounds.grpc.config.ConfigDocument
import gg.grounds.grpc.config.SyncDefaultsRequest
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
internal class ConfigScopeSynchronizer(private val logger: Logger) : AutoCloseable {
    private val objectMapper: ObjectMapper =
        jacksonMapperBuilder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()
    private val refreshExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "config-scope-refresh").apply { isDaemon = true }
        }
    private val trackedScopes = ConcurrentHashMap.newKeySet<AppEnvScope>()
    private var grpcClient: GrpcConfigClient? = null
    private var natsListener: NatsConfigListener? = null
    private var refreshFuture: ScheduledFuture<*>? = null

    fun start(grpcTarget: String, natsUrl: String) {
        close()
        val client = GrpcConfigClient.create(grpcTarget)
        val listener = NatsConfigListener(logger)
        listener.start(natsUrl)
        grpcClient = client
        natsListener = listener
        refreshFuture =
            refreshExecutor.scheduleWithFixedDelay(
                { refreshTrackedScopes() },
                REFRESH_INTERVAL_SECONDS,
                REFRESH_INTERVAL_SECONDS,
                TimeUnit.SECONDS,
            )
    }

    fun bootstrap(scope: AppEnvScope, binding: ConfigBinding<*>) {
        val client = grpcClient ?: return
        val listener = natsListener ?: return
        trackedScopes.add(scope)
        scope.withRefreshLock {
            syncDefault(client, scope, binding)
            try {
                refreshScope(client, scope, forceFullSnapshot = true)
            } catch (error: Exception) {
                logger.error(
                    "Config snapshot load failed (app={}, env={}, reason=initial_snapshot_failed)",
                    scope.app,
                    scope.env,
                    error,
                )
            }
            subscribeToChanges(listener, scope)
        }
    }

    override fun close() {
        refreshFuture?.cancel(false)
        refreshFuture = null
        trackedScopes.clear()
        natsListener?.close()
        natsListener = null
        grpcClient?.close()
        grpcClient = null
    }

    private fun subscribeToChanges(listener: NatsConfigListener, scope: AppEnvScope) {
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
        client: GrpcConfigClient,
        scope: AppEnvScope,
        binding: ConfigBinding<*>,
    ) {
        try {
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
            val response = client.syncDefaults(request)
            if (response.createdKeysList.isNotEmpty()) {
                val createdKeys =
                    response.createdKeysList.map { createdKey ->
                        "${createdKey.namespace}/${createdKey.configKey}"
                    }
                logger.info(
                    "Config default synced (app={}, env={}, createdKeys={})",
                    scope.app,
                    scope.env,
                    createdKeys,
                )
            }
        } catch (error: Exception) {
            logger.error(
                "Config default sync failed (app={}, env={}, namespace={}, key={})",
                scope.app,
                scope.env,
                binding.definition.namespace,
                binding.definition.key,
                error,
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
        client: GrpcConfigClient,
        scope: AppEnvScope,
        forceFullSnapshot: Boolean,
    ) {
        val requiresFullSnapshot = forceFullSnapshot || scope.hasUninitializedBindings()
        val response =
            if (requiresFullSnapshot) {
                client.getSnapshot(scope.app, scope.env)
            } else {
                client.getSnapshotIfNewer(scope.app, scope.env, scope.version())
            }
        if (response.changed) {
            applySnapshot(scope, response.version, response.documentsList)
        }
    }

    private fun applySnapshot(scope: AppEnvScope, version: Long, documents: List<ConfigDocument>) {
        val documentsByKey =
            documents.associateBy { document -> ConfigKey(document.namespace, document.configKey) }
        for ((configKey, binding) in scope.bindingsSnapshot()) {
            val document = documentsByKey[configKey]
            if (document != null) {
                applyDocument(binding, document.contentJson)
            } else {
                binding.resetToDefault()
            }
        }
        val previousVersion = scope.setVersion(version)
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

    companion object {
        private const val REFRESH_INTERVAL_SECONDS = 15L
    }
}
