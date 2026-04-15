package gg.grounds.config.internal.sync

import gg.grounds.config.ConfigRegistrationException
import gg.grounds.config.ConfigRegistrationResult
import gg.grounds.config.ConfigSnapshot
import gg.grounds.config.ConfigStartupMode
import gg.grounds.config.grpc.ConfigSyncClient
import gg.grounds.config.internal.binding.ConfigBinding
import gg.grounds.config.internal.scope.AppEnvScope
import gg.grounds.config.nats.ConfigChangeListener
import gg.grounds.grpc.config.ConfigDefault
import gg.grounds.grpc.config.SyncDefaultsRequest
import org.slf4j.Logger
import tools.jackson.databind.ObjectMapper

internal class BootstrapCoordinator(
    private val logger: Logger,
    private val objectMapper: ObjectMapper,
    private val refreshScheduler: RefreshScheduler,
    private val snapshotApplier: SnapshotApplier,
    private val sleepMillis: (Long) -> Unit,
    private val snapshotCacheLoader: (String, String) -> ConfigSnapshot?,
) {
    fun bootstrap(
        client: ConfigSyncClient,
        listener: ConfigChangeListener,
        scope: AppEnvScope,
        binding: ConfigBinding<*>,
        startupMode: ConfigStartupMode,
    ): ConfigRegistrationResult {
        refreshScheduler.trackScope(scope)
        var registrationResult = ConfigRegistrationResult.ready(scope.version())
        scope.withRefreshLock {
            var bootstrapFailure: Exception?
            bootstrapFailure =
                try {
                    syncDefault(client, scope, binding)
                    null
                } catch (error: Exception) {
                    error
                }
            refreshScheduler.subscribeToChanges(listener, scope)
            if (bootstrapFailure == null) {
                bootstrapFailure =
                    try {
                        refreshScheduler.refreshScope(client, scope, forceFullSnapshot = true)
                        null
                    } catch (error: Exception) {
                        error
                    }
            }
            registrationResult =
                if (bootstrapFailure != null) {
                    handleBootstrapFailure(scope, binding, startupMode, bootstrapFailure)
                } else if (binding.initialized()) {
                    ConfigRegistrationResult.ready(scope.version())
                } else {
                    handleUninitializedBinding(scope, binding, startupMode)
                }
        }
        return registrationResult
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
            executeRetryableGrpcCall(
                logger = logger,
                scope = scope,
                operation = "sync_defaults",
                maxAttempts = BOOTSTRAP_GRPC_MAX_ATTEMPTS,
                sleepMillis = sleepMillis,
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
            val cachedSnapshot = snapshotCacheLoader(scope.app, scope.env)
            if (cachedSnapshot != null) {
                snapshotApplier.applyCachedSnapshot(scope, cachedSnapshot)
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

    private companion object {
        private const val BOOTSTRAP_GRPC_MAX_ATTEMPTS = 3
    }
}
