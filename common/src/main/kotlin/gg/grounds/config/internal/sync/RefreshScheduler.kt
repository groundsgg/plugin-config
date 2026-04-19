package gg.grounds.config.internal.sync

import gg.grounds.config.internal.scope.AppEnvScope
import gg.grounds.config.nats.ConfigChangeListener
import gg.grounds.grpc.config.GetSnapshotResponse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.slf4j.Logger

internal class RefreshScheduler(
    private val logger: Logger,
    private val snapshotApplier: SnapshotApplier,
    private val refreshExecutorFactory: () -> ScheduledExecutorService,
    private val refreshWorkerExecutorFactory: () -> ExecutorService,
    private val sleepMillis: (Long) -> Unit,
    private val clientProvider: () -> gg.grounds.config.grpc.ConfigSyncClient?,
    private val withLifecycleReadLock: ((() -> Unit) -> Unit),
) {
    private val trackedScopes = ConcurrentHashMap.newKeySet<AppEnvScope>()
    private var refreshExecutor: ScheduledExecutorService? = null
    private var refreshFuture: ScheduledFuture<*>? = null
    private var refreshWorkerExecutor: ExecutorService? = null

    fun start() {
        val executor = ensureRefreshExecutor()
        ensureRefreshWorkerExecutor()
        refreshFuture =
            executor.scheduleWithFixedDelay(
                { refreshTrackedScopes() },
                REFRESH_INTERVAL_SECONDS,
                REFRESH_INTERVAL_SECONDS,
                TimeUnit.SECONDS,
            )
    }

    fun stopRuntime() {
        refreshFuture?.cancel(false)
        refreshFuture = null
        refreshWorkerExecutor?.shutdownNow()
        refreshWorkerExecutor = null
        trackedScopes.clear()
    }

    fun close() {
        refreshExecutor?.shutdownNow()
        refreshExecutor = null
    }

    fun trackScope(scope: AppEnvScope) {
        trackedScopes.add(scope)
    }

    fun subscribeToChanges(listener: ConfigChangeListener, scope: AppEnvScope) {
        if (!scope.markSubscriptionStarted()) {
            return
        }
        listener.subscribe(scope.app, scope.env) { onNatsChangeReceived(scope) }
    }

    fun refreshTrackedScopes() {
        withLifecycleReadLock {
            val client = clientProvider() ?: return@withLifecycleReadLock
            val workerExecutor = refreshWorkerExecutor ?: return@withLifecycleReadLock
            for (scope in trackedScopes) {
                workerExecutor.submit {
                    withLifecycleReadLock {
                        scope.tryWithRefreshLock {
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
            }
        }
    }

    fun refreshScope(
        client: gg.grounds.config.grpc.ConfigSyncClient,
        scope: AppEnvScope,
        forceFullSnapshot: Boolean,
    ) {
        val requiresFullSnapshot = forceFullSnapshot || scope.hasUninitializedBindings()
        val response =
            if (requiresFullSnapshot) {
                executeRetryableGrpcCall(
                    logger = logger,
                    scope = scope,
                    operation = "get_snapshot",
                    maxAttempts = BOOTSTRAP_GRPC_MAX_ATTEMPTS,
                    sleepMillis = sleepMillis,
                ) {
                    client.getSnapshot(scope.app, scope.env)
                }
            } else {
                executeRetryableGrpcCall(
                    logger = logger,
                    scope = scope,
                    operation = "get_snapshot_if_newer",
                    maxAttempts = REFRESH_GRPC_MAX_ATTEMPTS,
                    sleepMillis = sleepMillis,
                ) {
                    client.getSnapshotIfNewer(scope.app, scope.env, scope.version())
                }
            }
        applyResponse(scope, response)
    }

    private fun applyResponse(scope: AppEnvScope, response: GetSnapshotResponse) {
        if (response.changed) {
            snapshotApplier.applySnapshot(scope, response.version, response.documentsList)
        }
    }

    private fun onNatsChangeReceived(scope: AppEnvScope) {
        withLifecycleReadLock {
            val client = clientProvider() ?: return@withLifecycleReadLock
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

    private fun ensureRefreshWorkerExecutor(): ExecutorService {
        val executor = refreshWorkerExecutor
        if (executor != null && !executor.isShutdown && !executor.isTerminated) {
            return executor
        }
        return refreshWorkerExecutorFactory().also { createdExecutor ->
            refreshWorkerExecutor = createdExecutor
        }
    }

    private companion object {
        private const val REFRESH_INTERVAL_SECONDS = 15L
        private const val BOOTSTRAP_GRPC_MAX_ATTEMPTS = 3
        private const val REFRESH_GRPC_MAX_ATTEMPTS = 2
    }
}
