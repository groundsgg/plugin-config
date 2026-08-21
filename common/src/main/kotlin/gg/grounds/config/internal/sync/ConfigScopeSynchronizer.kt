package gg.grounds.config.internal.sync

import gg.grounds.config.ConfigRegistrationResult
import gg.grounds.config.ConfigStartupMode
import gg.grounds.config.client.ConfigSyncClient
import gg.grounds.config.client.HttpConfigClient
import gg.grounds.config.internal.binding.ConfigBinding
import gg.grounds.config.internal.cache.ConfigSnapshotCache
import gg.grounds.config.internal.scope.AppEnvScope
import gg.grounds.config.nats.ConfigChangeListener
import gg.grounds.config.nats.NatsConfigListener
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import org.slf4j.Logger
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonMapperBuilder

/** Internal coordinator for syncing defaults and applying snapshots to scope bindings. */
internal class ConfigScopeSynchronizer(
    private val logger: Logger,
    private val configClientFactory: (String) -> ConfigSyncClient = { target ->
        HttpConfigClient.create(target)
    },
    private val natsListenerFactory: (Logger) -> ConfigChangeListener = { syncLogger ->
        NatsConfigListener(syncLogger)
    },
    refreshExecutorFactory: () -> ScheduledExecutorService = {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "config-scope-refresh").apply { isDaemon = true }
        }
    },
    refreshWorkerExecutorFactory: () -> ExecutorService = {
        val threadCounter = AtomicInteger(1)
        Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceAtLeast(2)) {
            runnable ->
            Thread(runnable, "config-scope-refresh-worker-${threadCounter.getAndIncrement()}")
                .apply { isDaemon = true }
        }
    },
    sleepMillis: (Long) -> Unit = { delayMs -> Thread.sleep(delayMs) },
) : AutoCloseable {
    private val objectMapper: ObjectMapper =
        jacksonMapperBuilder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()
    private val lifecycleLock = ReentrantReadWriteLock(true)
    private val snapshotApplier =
        SnapshotApplier(
            logger = logger,
            objectMapper = objectMapper,
            snapshotCacheProvider = { snapshotCache },
        )
    private val refreshScheduler =
        RefreshScheduler(
            logger = logger,
            objectMapper = objectMapper,
            snapshotApplier = snapshotApplier,
            refreshExecutorFactory = refreshExecutorFactory,
            refreshWorkerExecutorFactory = refreshWorkerExecutorFactory,
            sleepMillis = sleepMillis,
            clientProvider = { configClient },
            withLifecycleReadLock = { block -> lifecycleLock.read { block() } },
        )
    private val bootstrapCoordinator =
        BootstrapCoordinator(
            logger = logger,
            objectMapper = objectMapper,
            refreshScheduler = refreshScheduler,
            snapshotApplier = snapshotApplier,
            sleepMillis = sleepMillis,
            snapshotCacheLoader = { app, env -> snapshotCache.load(app, env) },
        )
    private var configClient: ConfigSyncClient? = null
    private var natsListener: ConfigChangeListener? = null
    private var snapshotCache: ConfigSnapshotCache = ConfigSnapshotCache.noop()

    fun start(serviceUrl: String, natsUrl: String, cacheDirectory: Path? = null) {
        lifecycleLock.write {
            stopRuntime()
            val client = configClientFactory(serviceUrl)
            val listener = natsListenerFactory(logger)
            listener.start(natsUrl)
            configClient = client
            natsListener = listener
            snapshotCache = ConfigSnapshotCache.create(logger, cacheDirectory)
            refreshScheduler.start()
        }
    }

    fun bootstrap(
        scope: AppEnvScope,
        binding: ConfigBinding<*>,
        startupMode: ConfigStartupMode,
    ): ConfigRegistrationResult =
        lifecycleLock.read {
            val client =
                checkNotNull(configClient) {
                    "Config scope bootstrap failed (reason=grpc_client_not_started)"
                }
            val listener =
                checkNotNull(natsListener) {
                    "Config scope bootstrap failed (reason=nats_listener_not_started)"
                }
            bootstrapCoordinator.bootstrap(client, listener, scope, binding, startupMode)
        }

    override fun close() {
        lifecycleLock.write {
            stopRuntime()
            refreshScheduler.close()
        }
    }

    private fun stopRuntime() {
        refreshScheduler.stopRuntime()
        natsListener?.close()
        natsListener = null
        configClient?.close()
        configClient = null
        snapshotCache = ConfigSnapshotCache.noop()
    }

    private fun refreshTrackedScopes() {
        refreshScheduler.refreshTrackedScopes()
    }
}
