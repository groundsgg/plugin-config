package gg.grounds.config.internal.sync

import gg.grounds.config.ConfigKey
import gg.grounds.config.grpc.GrpcConfigClient
import gg.grounds.config.internal.binding.ConfigBinding
import gg.grounds.config.internal.scope.AppEnvScope
import gg.grounds.config.nats.NatsConfigListener
import gg.grounds.grpc.config.ConfigDefault
import gg.grounds.grpc.config.ConfigDocument
import gg.grounds.grpc.config.SyncDefaultsRequest
import org.slf4j.Logger
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonMapperBuilder

/** Internal coordinator for syncing defaults and applying snapshots to scope bindings. */
internal class ConfigScopeSynchronizer(private val logger: Logger) : AutoCloseable {
    private val objectMapper: ObjectMapper =
        jacksonMapperBuilder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()
    private var grpcClient: GrpcConfigClient? = null
    private var natsListener: NatsConfigListener? = null

    fun start(grpcTarget: String, natsUrl: String) {
        close()
        val client = GrpcConfigClient.create(grpcTarget)
        val listener = NatsConfigListener(logger)
        listener.start(natsUrl)
        grpcClient = client
        natsListener = listener
    }

    fun bootstrap(scope: AppEnvScope, binding: ConfigBinding<*>) {
        val client = grpcClient ?: return
        val listener = natsListener ?: return
        scope.withRefreshLock {
            syncDefault(client, scope, binding)
            loadSnapshot(client, scope)
            subscribeToChanges(listener, scope)
        }
    }

    override fun close() {
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
                val response = client.getSnapshotIfNewer(scope.app, scope.env, scope.version())
                if (response.changed) {
                    applySnapshot(scope, response.version, response.documentsList)
                }
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
                logger.info(
                    "Config default synced (app={}, env={}, createdKeys={})",
                    scope.app,
                    scope.env,
                    response.createdKeysList,
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

    private fun loadSnapshot(client: GrpcConfigClient, scope: AppEnvScope) {
        try {
            val response = client.getSnapshot(scope.app, scope.env)
            applySnapshot(scope, response.version, response.documentsList)
        } catch (error: Exception) {
            logger.error(
                "Config snapshot load failed (app={}, env={}, reason=initial_snapshot_failed)",
                scope.app,
                scope.env,
                error,
            )
        }
    }

    private fun applySnapshot(scope: AppEnvScope, version: Long, documents: List<ConfigDocument>) {
        for (document in documents) {
            val configKey = ConfigKey(document.namespace, document.configKey)
            val binding = scope.binding(configKey) ?: continue
            applyDocument(binding, document.contentJson)
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
}
