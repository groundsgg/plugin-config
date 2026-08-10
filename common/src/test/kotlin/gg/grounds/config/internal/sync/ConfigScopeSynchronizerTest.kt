package gg.grounds.config.internal.sync

import gg.grounds.config.ConfigDefinition
import gg.grounds.config.ConfigKey
import gg.grounds.config.ConfigRegistrationException
import gg.grounds.config.ConfigRegistrationResult
import gg.grounds.config.ConfigRegistrationStatus
import gg.grounds.config.ConfigStartupMode
import gg.grounds.config.client.ConfigDefaultData
import gg.grounds.config.client.ConfigDocumentData
import gg.grounds.config.client.ConfigServiceException
import gg.grounds.config.client.ConfigSyncClient
import gg.grounds.config.client.SnapshotResult
import gg.grounds.config.client.SyncDefaultsResult
import gg.grounds.config.internal.binding.ConfigBinding
import gg.grounds.config.internal.scope.AppEnvScope
import gg.grounds.config.nats.ConfigChangeListener
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory

class ConfigScopeSynchronizerTest {
    @Test
    fun `close waits for bootstrap lifecycle operation`() {
        val snapshotStarted = CountDownLatch(1)
        val allowSnapshotCompletion = CountDownLatch(1)
        val bootstrapExecutor = Executors.newSingleThreadExecutor()
        val closeExecutor = Executors.newSingleThreadExecutor()
        val executors = mutableListOf<ScheduledExecutorService>()
        val client =
            RecordingConfigSyncClient(
                getSnapshotHandler = {
                    snapshotStarted.countDown()
                    assertTrue(allowSnapshotCompletion.await(1, TimeUnit.SECONDS))
                    defaultSnapshotResponse(version = 1, value = "initial")
                }
            )
        val synchronizer =
            ConfigScopeSynchronizer(
                logger = LoggerFactory.getLogger("ConfigScopeSynchronizerLifecycleLockTest"),
                configClientFactory = { client },
                natsListenerFactory = { RecordingConfigChangeListener() },
                refreshExecutorFactory = {
                    Executors.newSingleThreadScheduledExecutor().also { executor ->
                        executors += executor
                    }
                },
            )
        val scope = AppEnvScope(app = "test-app", env = "dev")
        val binding = ConfigBinding(TestStringConfig)
        scope.putBindingIfAbsent(
            ConfigKey(TestStringConfig.namespace, TestStringConfig.key),
            binding,
        )

        try {
            synchronizer.start("dns:///config", "nats://localhost:4222")

            val bootstrapFuture: Future<ConfigRegistrationResult> =
                bootstrapExecutor.submit<ConfigRegistrationResult> {
                    synchronizer.bootstrap(scope, binding, ConfigStartupMode.FAIL_CLOSED)
                }

            assertTrue(snapshotStarted.await(1, TimeUnit.SECONDS))

            val closeFuture = closeExecutor.submit { synchronizer.close() }

            assertFalse(closeFuture.isDone)

            allowSnapshotCompletion.countDown()

            assertEquals(
                ConfigRegistrationStatus.READY,
                bootstrapFuture.get(1, TimeUnit.SECONDS).status,
            )
            closeFuture.get(1, TimeUnit.SECONDS)
        } finally {
            synchronizer.close()
            executors.forEach { executor -> executor.shutdownNow() }
            bootstrapExecutor.shutdownNow()
            closeExecutor.shutdownNow()
        }
    }

    @Test
    fun `periodic refresh dispatches scopes in parallel`() {
        val operations = Collections.synchronizedList(mutableListOf<String>())
        val activeRefreshes = AtomicInteger(0)
        val maxConcurrentRefreshes = AtomicInteger(0)
        val refreshCompleted = CountDownLatch(2)
        val client =
            RecordingConfigSyncClient(
                operations = operations,
                getSnapshotHandler = { defaultSnapshotResponse(version = 1, value = "initial") },
                getSnapshotIfNewerHandler = { _, _, _ ->
                    val concurrentRefreshes = activeRefreshes.incrementAndGet()
                    maxConcurrentRefreshes.updateAndGet { currentMax ->
                        maxOf(currentMax, concurrentRefreshes)
                    }
                    try {
                        Thread.sleep(200)
                        SnapshotResult(changed = false, version = 0, documents = emptyList())
                    } finally {
                        activeRefreshes.decrementAndGet()
                        refreshCompleted.countDown()
                    }
                },
            )
        val workerExecutors = mutableListOf<ExecutorService>()
        val schedulerExecutors = mutableListOf<ScheduledExecutorService>()
        val synchronizer =
            ConfigScopeSynchronizer(
                logger = LoggerFactory.getLogger("ConfigScopeSynchronizerParallelRefreshTest"),
                configClientFactory = { client },
                natsListenerFactory = { RecordingConfigChangeListener() },
                refreshExecutorFactory = {
                    Executors.newSingleThreadScheduledExecutor().also { executor ->
                        schedulerExecutors += executor
                    }
                },
                refreshWorkerExecutorFactory = {
                    Executors.newFixedThreadPool(2).also { executor -> workerExecutors += executor }
                },
            )
        val firstScope = AppEnvScope(app = "first-app", env = "dev")
        val firstBinding = ConfigBinding(TestStringConfig)
        firstScope.putBindingIfAbsent(
            ConfigKey(TestStringConfig.namespace, TestStringConfig.key),
            firstBinding,
        )
        val secondScope = AppEnvScope(app = "second-app", env = "dev")
        val secondBinding = ConfigBinding(TestStringConfig)
        secondScope.putBindingIfAbsent(
            ConfigKey(TestStringConfig.namespace, TestStringConfig.key),
            secondBinding,
        )

        try {
            synchronizer.start("dns:///config", "nats://localhost:4222")
            synchronizer.bootstrap(firstScope, firstBinding, ConfigStartupMode.FAIL_CLOSED)
            synchronizer.bootstrap(secondScope, secondBinding, ConfigStartupMode.FAIL_CLOSED)

            invokeRefreshTrackedScopes(synchronizer)

            assertTrue(refreshCompleted.await(1, TimeUnit.SECONDS))
            assertEquals(2, maxConcurrentRefreshes.get())
        } finally {
            synchronizer.close()
            schedulerExecutors.forEach { executor -> executor.shutdownNow() }
            workerExecutors.forEach { executor -> executor.shutdownNow() }
        }
    }

    @Test
    fun `bootstrap subscribes before initial snapshot and applies concurrent update`() {
        val operations = Collections.synchronizedList(mutableListOf<String>())
        val callbackStarted = CountDownLatch(1)
        val callbackCompleted = CountDownLatch(1)
        val client =
            RecordingConfigSyncClient(
                operations = operations,
                getSnapshotHandler = {
                    callbackStarted.await(1, TimeUnit.SECONDS)
                    defaultSnapshotResponse(version = 1, value = "initial")
                },
                getSnapshotIfNewerHandler = { _, _, _ ->
                    defaultSnapshotResponse(version = 2, value = "updated")
                },
            )
        val listener =
            RecordingConfigChangeListener(operations) { onChangeReceived ->
                Thread {
                        callbackStarted.countDown()
                        onChangeReceived()
                        callbackCompleted.countDown()
                    }
                    .start()
            }
        val executors = mutableListOf<ScheduledExecutorService>()
        val synchronizer =
            ConfigScopeSynchronizer(
                logger = LoggerFactory.getLogger("ConfigScopeSynchronizerTest"),
                configClientFactory = { client },
                natsListenerFactory = { listener },
                refreshExecutorFactory = {
                    Executors.newSingleThreadScheduledExecutor().also { executor ->
                        executors += executor
                    }
                },
            )
        val scope = AppEnvScope(app = "test-app", env = "dev")
        val binding = ConfigBinding(TestStringConfig)
        scope.putBindingIfAbsent(
            ConfigKey(TestStringConfig.namespace, TestStringConfig.key),
            binding,
        )

        try {
            synchronizer.start("dns:///config", "nats://localhost:4222")
            synchronizer.bootstrap(scope, binding, ConfigStartupMode.FAIL_CLOSED)

            assertTrue(callbackCompleted.await(1, TimeUnit.SECONDS))
            assertEquals(
                listOf("syncDefaults", "subscribe", "getSnapshot", "getSnapshotIfNewer"),
                operations,
            )
            assertEquals("updated", binding.get())
            assertEquals(2, scope.version())
        } finally {
            synchronizer.close()
            executors.forEach { executor -> executor.shutdownNow() }
        }
    }

    @Test
    fun `bootstrap retries transient grpc failures`() {
        val retryDelays = CopyOnWriteArrayList<Long>()
        val client =
            RecordingConfigSyncClient(
                syncDefaultsHandler = {
                    if (syncDefaultCalls < 3) {
                        throw ConfigServiceException("sync unavailable", 503)
                    }
                    SyncDefaultsResult(version = 0, createdKeys = emptyList())
                },
                getSnapshotHandler = {
                    if (snapshotCalls < 3) {
                        throw ConfigServiceException("snapshot unavailable", 503)
                    }
                    defaultSnapshotResponse(version = 7, value = "ready")
                },
            )
        val listener = RecordingConfigChangeListener()
        val executors = mutableListOf<ScheduledExecutorService>()
        val synchronizer =
            ConfigScopeSynchronizer(
                logger = LoggerFactory.getLogger("ConfigScopeSynchronizerRetryTest"),
                configClientFactory = { client },
                natsListenerFactory = { listener },
                refreshExecutorFactory = {
                    Executors.newSingleThreadScheduledExecutor().also { executor ->
                        executors += executor
                    }
                },
                sleepMillis = { delayMs -> retryDelays += delayMs },
            )
        val scope = AppEnvScope(app = "test-app", env = "dev")
        val binding = ConfigBinding(TestStringConfig)
        scope.putBindingIfAbsent(
            ConfigKey(TestStringConfig.namespace, TestStringConfig.key),
            binding,
        )

        try {
            synchronizer.start("dns:///config", "nats://localhost:4222")
            synchronizer.bootstrap(scope, binding, ConfigStartupMode.FAIL_CLOSED)

            assertEquals(3, client.syncDefaultCalls)
            assertEquals(3, client.snapshotCalls)
            assertEquals(listOf(250L, 500L, 250L, 500L), retryDelays)
            assertTrue(binding.initialized())
            assertEquals("ready", binding.get())
            assertEquals(7, scope.version())
        } finally {
            synchronizer.close()
            executors.forEach { executor -> executor.shutdownNow() }
        }
    }

    @Test
    fun `bootstrap fails closed when initial snapshot load fails`() {
        val client =
            RecordingConfigSyncClient(
                getSnapshotHandler = { throw ConfigServiceException("snapshot unavailable", 503) }
            )
        val listener = RecordingConfigChangeListener()
        val executors = mutableListOf<ScheduledExecutorService>()
        val synchronizer =
            ConfigScopeSynchronizer(
                logger = LoggerFactory.getLogger("ConfigScopeSynchronizerFailClosedTest"),
                configClientFactory = { client },
                natsListenerFactory = { listener },
                refreshExecutorFactory = {
                    Executors.newSingleThreadScheduledExecutor().also { executor ->
                        executors += executor
                    }
                },
                sleepMillis = {},
            )
        val scope = AppEnvScope(app = "test-app", env = "dev")
        val binding = ConfigBinding(TestStringConfig)
        scope.putBindingIfAbsent(
            ConfigKey(TestStringConfig.namespace, TestStringConfig.key),
            binding,
        )

        try {
            synchronizer.start("dns:///config", "nats://localhost:4222")

            assertFailsWith<ConfigRegistrationException> {
                synchronizer.bootstrap(scope, binding, ConfigStartupMode.FAIL_CLOSED)
            }
        } finally {
            synchronizer.close()
            executors.forEach { executor -> executor.shutdownNow() }
        }
    }

    @Test
    fun `bootstrap loads cached snapshot during degraded start`() {
        val cacheDirectory = createTempDirectory("config-scope-cache")
        val executors = mutableListOf<ScheduledExecutorService>()
        try {
            val warmupSynchronizer =
                ConfigScopeSynchronizer(
                    logger = LoggerFactory.getLogger("ConfigScopeSynchronizerWarmupTest"),
                    configClientFactory = {
                        RecordingConfigSyncClient(
                            getSnapshotHandler = {
                                defaultSnapshotResponse(version = 11, value = "cached")
                            }
                        )
                    },
                    natsListenerFactory = { RecordingConfigChangeListener() },
                    refreshExecutorFactory = {
                        Executors.newSingleThreadScheduledExecutor().also { executor ->
                            executors += executor
                        }
                    },
                )
            val warmupScope = AppEnvScope(app = "test-app", env = "dev")
            val warmupBinding = ConfigBinding(TestStringConfig)
            warmupScope.putBindingIfAbsent(
                ConfigKey(TestStringConfig.namespace, TestStringConfig.key),
                warmupBinding,
            )
            warmupSynchronizer.start("dns:///config", "nats://localhost:4222", cacheDirectory)
            warmupSynchronizer.bootstrap(warmupScope, warmupBinding, ConfigStartupMode.FAIL_CLOSED)
            warmupSynchronizer.close()

            val degradedSynchronizer =
                ConfigScopeSynchronizer(
                    logger = LoggerFactory.getLogger("ConfigScopeSynchronizerDegradedCacheTest"),
                    configClientFactory = {
                        RecordingConfigSyncClient(
                            syncDefaultsHandler = {
                                throw ConfigServiceException("sync unavailable", 503)
                            }
                        )
                    },
                    natsListenerFactory = { RecordingConfigChangeListener() },
                    refreshExecutorFactory = {
                        Executors.newSingleThreadScheduledExecutor().also { executor ->
                            executors += executor
                        }
                    },
                    sleepMillis = {},
                )
            val degradedScope = AppEnvScope(app = "test-app", env = "dev")
            val degradedBinding = ConfigBinding(TestStringConfig)
            degradedScope.putBindingIfAbsent(
                ConfigKey(TestStringConfig.namespace, TestStringConfig.key),
                degradedBinding,
            )

            degradedSynchronizer.start("dns:///config", "nats://localhost:4222", cacheDirectory)
            val result =
                degradedSynchronizer.bootstrap(
                    degradedScope,
                    degradedBinding,
                    ConfigStartupMode.DEGRADED,
                )

            assertEquals(ConfigRegistrationStatus.DEGRADED, result.status)
            assertEquals("loaded_cached_snapshot", result.reason)
            assertEquals(11, result.version)
            assertTrue(degradedBinding.initialized())
            assertEquals("cached", degradedBinding.get())
            assertEquals(11, degradedScope.version())
            degradedSynchronizer.close()
        } finally {
            cacheDirectory.toFile().deleteRecursively()
            executors.forEach { executor -> executor.shutdownNow() }
        }
    }

    @Test
    fun `bootstrap returns not ready during degraded start without cached snapshot`() {
        val cacheDirectory = createTempDirectory("config-scope-empty-cache")
        val client =
            RecordingConfigSyncClient(
                syncDefaultsHandler = { throw ConfigServiceException("sync unavailable", 503) }
            )
        val listener = RecordingConfigChangeListener()
        val executors = mutableListOf<ScheduledExecutorService>()
        val synchronizer =
            ConfigScopeSynchronizer(
                logger = LoggerFactory.getLogger("ConfigScopeSynchronizerNotReadyTest"),
                configClientFactory = { client },
                natsListenerFactory = { listener },
                refreshExecutorFactory = {
                    Executors.newSingleThreadScheduledExecutor().also { executor ->
                        executors += executor
                    }
                },
                sleepMillis = {},
            )
        val scope = AppEnvScope(app = "test-app", env = "dev")
        val binding = ConfigBinding(TestStringConfig)
        scope.putBindingIfAbsent(
            ConfigKey(TestStringConfig.namespace, TestStringConfig.key),
            binding,
        )

        try {
            synchronizer.start("dns:///config", "nats://localhost:4222", cacheDirectory)
            val result = synchronizer.bootstrap(scope, binding, ConfigStartupMode.DEGRADED)

            assertEquals(ConfigRegistrationStatus.NOT_READY, result.status)
            assertEquals("bootstrap_failed_no_cached_snapshot", result.reason)
            assertFalse(binding.initialized())
        } finally {
            synchronizer.close()
            cacheDirectory.toFile().deleteRecursively()
            executors.forEach { executor -> executor.shutdownNow() }
        }
    }

    @Test
    fun `bootstrap leaves binding uninitialized when snapshot is missing document`() {
        val client =
            RecordingConfigSyncClient(
                getSnapshotHandler = {
                    SnapshotResult(changed = true, version = 3, documents = emptyList())
                }
            )
        val listener = RecordingConfigChangeListener()
        val executors = mutableListOf<ScheduledExecutorService>()
        val synchronizer =
            ConfigScopeSynchronizer(
                logger = LoggerFactory.getLogger("ConfigScopeSynchronizerMissingBootstrapTest"),
                configClientFactory = { client },
                natsListenerFactory = { listener },
                refreshExecutorFactory = {
                    Executors.newSingleThreadScheduledExecutor().also { executor ->
                        executors += executor
                    }
                },
            )
        val scope = AppEnvScope(app = "test-app", env = "dev")
        val binding = ConfigBinding(TestStringConfig)
        scope.putBindingIfAbsent(
            ConfigKey(TestStringConfig.namespace, TestStringConfig.key),
            binding,
        )

        try {
            synchronizer.start("dns:///config", "nats://localhost:4222")
            val result = synchronizer.bootstrap(scope, binding, ConfigStartupMode.DEGRADED)

            assertEquals(ConfigRegistrationStatus.NOT_READY, result.status)
            assertFalse(binding.initialized())
            assertEquals("default", binding.get())
            assertEquals(3, scope.version())
        } finally {
            synchronizer.close()
            executors.forEach { executor -> executor.shutdownNow() }
        }
    }

    @Test
    fun `refresh keeps previous value when snapshot is missing document`() {
        val client =
            RecordingConfigSyncClient(
                getSnapshotHandler = { defaultSnapshotResponse(version = 1, value = "initial") },
                getSnapshotIfNewerHandler = { _, _, _ ->
                    SnapshotResult(changed = true, version = 2, documents = emptyList())
                },
            )
        var onChangeReceived: (() -> Unit)? = null
        val listener = RecordingConfigChangeListener { callback -> onChangeReceived = callback }
        val executors = mutableListOf<ScheduledExecutorService>()
        val synchronizer =
            ConfigScopeSynchronizer(
                logger = LoggerFactory.getLogger("ConfigScopeSynchronizerMissingRefreshTest"),
                configClientFactory = { client },
                natsListenerFactory = { listener },
                refreshExecutorFactory = {
                    Executors.newSingleThreadScheduledExecutor().also { executor ->
                        executors += executor
                    }
                },
            )
        val scope = AppEnvScope(app = "test-app", env = "dev")
        val binding = ConfigBinding(TestStringConfig)
        scope.putBindingIfAbsent(
            ConfigKey(TestStringConfig.namespace, TestStringConfig.key),
            binding,
        )

        try {
            synchronizer.start("dns:///config", "nats://localhost:4222")
            synchronizer.bootstrap(scope, binding, ConfigStartupMode.FAIL_CLOSED)
            onChangeReceived?.invoke()

            assertTrue(binding.initialized())
            assertEquals("initial", binding.get())
            assertEquals(2, scope.version())
        } finally {
            synchronizer.close()
            executors.forEach { executor -> executor.shutdownNow() }
        }
    }

    @Test
    fun `refresh ignores stale snapshot version`() {
        val client =
            RecordingConfigSyncClient(
                getSnapshotHandler = { defaultSnapshotResponse(version = 2, value = "updated") },
                getSnapshotIfNewerHandler = { _, _, _ ->
                    defaultSnapshotResponse(version = 1, value = "stale")
                },
            )
        var onChangeReceived: (() -> Unit)? = null
        val listener = RecordingConfigChangeListener { callback -> onChangeReceived = callback }
        val executors = mutableListOf<ScheduledExecutorService>()
        val synchronizer =
            ConfigScopeSynchronizer(
                logger = LoggerFactory.getLogger("ConfigScopeSynchronizerStaleSnapshotTest"),
                configClientFactory = { client },
                natsListenerFactory = { listener },
                refreshExecutorFactory = {
                    Executors.newSingleThreadScheduledExecutor().also { executor ->
                        executors += executor
                    }
                },
            )
        val scope = AppEnvScope(app = "test-app", env = "dev")
        val binding = ConfigBinding(TestStringConfig)
        scope.putBindingIfAbsent(
            ConfigKey(TestStringConfig.namespace, TestStringConfig.key),
            binding,
        )

        try {
            synchronizer.start("dns:///config", "nats://localhost:4222")
            synchronizer.bootstrap(scope, binding, ConfigStartupMode.FAIL_CLOSED)
            onChangeReceived?.invoke()

            assertTrue(binding.initialized())
            assertEquals("updated", binding.get())
            assertEquals(2, scope.version())
        } finally {
            synchronizer.close()
            executors.forEach { executor -> executor.shutdownNow() }
        }
    }

    @Test
    fun `degraded restart returns not ready after partial refresh cached snapshot`() {
        val cacheDirectory = createTempDirectory("config-scope-retained-cache")
        val executors = mutableListOf<ScheduledExecutorService>()
        var onChangeReceived: (() -> Unit)? = null

        try {
            val warmupSynchronizer =
                ConfigScopeSynchronizer(
                    logger = LoggerFactory.getLogger("ConfigScopeSynchronizerRetainedCacheWarmup"),
                    configClientFactory = {
                        RecordingConfigSyncClient(
                            getSnapshotHandler = {
                                defaultSnapshotResponse(version = 1, value = "initial")
                            },
                            getSnapshotIfNewerHandler = { _, _, _ ->
                                SnapshotResult(changed = true, version = 2, documents = emptyList())
                            },
                        )
                    },
                    natsListenerFactory = {
                        RecordingConfigChangeListener { callback -> onChangeReceived = callback }
                    },
                    refreshExecutorFactory = {
                        Executors.newSingleThreadScheduledExecutor().also { executor ->
                            executors += executor
                        }
                    },
                )
            val warmupScope = AppEnvScope(app = "test-app", env = "dev")
            val warmupBinding = ConfigBinding(TestStringConfig)
            warmupScope.putBindingIfAbsent(
                ConfigKey(TestStringConfig.namespace, TestStringConfig.key),
                warmupBinding,
            )

            warmupSynchronizer.start("dns:///config", "nats://localhost:4222", cacheDirectory)
            warmupSynchronizer.bootstrap(warmupScope, warmupBinding, ConfigStartupMode.FAIL_CLOSED)
            onChangeReceived?.invoke()

            assertTrue(warmupBinding.initialized())
            assertEquals("initial", warmupBinding.get())
            assertEquals(2, warmupScope.version())
            warmupSynchronizer.close()

            val degradedSynchronizer =
                ConfigScopeSynchronizer(
                    logger =
                        LoggerFactory.getLogger("ConfigScopeSynchronizerRetainedCacheDegraded"),
                    configClientFactory = {
                        RecordingConfigSyncClient(
                            syncDefaultsHandler = {
                                throw ConfigServiceException("sync unavailable", 503)
                            }
                        )
                    },
                    natsListenerFactory = { RecordingConfigChangeListener() },
                    refreshExecutorFactory = {
                        Executors.newSingleThreadScheduledExecutor().also { executor ->
                            executors += executor
                        }
                    },
                    sleepMillis = {},
                )
            val degradedScope = AppEnvScope(app = "test-app", env = "dev")
            val degradedBinding = ConfigBinding(TestStringConfig)
            degradedScope.putBindingIfAbsent(
                ConfigKey(TestStringConfig.namespace, TestStringConfig.key),
                degradedBinding,
            )

            degradedSynchronizer.start("dns:///config", "nats://localhost:4222", cacheDirectory)
            val result =
                degradedSynchronizer.bootstrap(
                    degradedScope,
                    degradedBinding,
                    ConfigStartupMode.DEGRADED,
                )

            assertEquals(ConfigRegistrationStatus.NOT_READY, result.status)
            assertEquals("bootstrap_failed_no_cached_snapshot", result.reason)
            assertEquals(null, result.version)
            assertFalse(degradedBinding.initialized())
            assertEquals("default", degradedBinding.get())
            assertEquals(2, degradedScope.version())
            degradedSynchronizer.close()
        } finally {
            cacheDirectory.toFile().deleteRecursively()
            executors.forEach { executor -> executor.shutdownNow() }
        }
    }

    @Test
    fun `degraded restart returns not ready after malformed document refresh cached snapshot`() {
        val cacheDirectory = createTempDirectory("config-scope-malformed-cache")
        val executors = mutableListOf<ScheduledExecutorService>()
        var onChangeReceived: (() -> Unit)? = null

        try {
            val warmupSynchronizer =
                ConfigScopeSynchronizer(
                    logger = LoggerFactory.getLogger("ConfigScopeSynchronizerMalformedCacheWarmup"),
                    configClientFactory = {
                        RecordingConfigSyncClient(
                            getSnapshotHandler = {
                                defaultSnapshotResponse(version = 1, value = "initial")
                            },
                            getSnapshotIfNewerHandler = { _, _, _ ->
                                SnapshotResult(
                                    changed = true,
                                    version = 2,
                                    documents =
                                        listOf(
                                            ConfigDocumentData(
                                                namespace = TestStringConfig.namespace,
                                                configKey = TestStringConfig.key,
                                                contentJson = "{",
                                            )
                                        ),
                                )
                            },
                        )
                    },
                    natsListenerFactory = {
                        RecordingConfigChangeListener { callback -> onChangeReceived = callback }
                    },
                    refreshExecutorFactory = {
                        Executors.newSingleThreadScheduledExecutor().also { executor ->
                            executors += executor
                        }
                    },
                )
            val warmupScope = AppEnvScope(app = "test-app", env = "dev")
            val warmupBinding = ConfigBinding(TestStringConfig)
            warmupScope.putBindingIfAbsent(
                ConfigKey(TestStringConfig.namespace, TestStringConfig.key),
                warmupBinding,
            )

            warmupSynchronizer.start("dns:///config", "nats://localhost:4222", cacheDirectory)
            warmupSynchronizer.bootstrap(warmupScope, warmupBinding, ConfigStartupMode.FAIL_CLOSED)
            onChangeReceived?.invoke()

            assertTrue(warmupBinding.initialized())
            assertEquals("initial", warmupBinding.get())
            assertEquals(2, warmupScope.version())
            warmupSynchronizer.close()

            val degradedSynchronizer =
                ConfigScopeSynchronizer(
                    logger =
                        LoggerFactory.getLogger("ConfigScopeSynchronizerMalformedCacheDegraded"),
                    configClientFactory = {
                        RecordingConfigSyncClient(
                            syncDefaultsHandler = {
                                throw ConfigServiceException("sync unavailable", 503)
                            }
                        )
                    },
                    natsListenerFactory = { RecordingConfigChangeListener() },
                    refreshExecutorFactory = {
                        Executors.newSingleThreadScheduledExecutor().also { executor ->
                            executors += executor
                        }
                    },
                    sleepMillis = {},
                )
            val degradedScope = AppEnvScope(app = "test-app", env = "dev")
            val degradedBinding = ConfigBinding(TestStringConfig)
            degradedScope.putBindingIfAbsent(
                ConfigKey(TestStringConfig.namespace, TestStringConfig.key),
                degradedBinding,
            )

            degradedSynchronizer.start("dns:///config", "nats://localhost:4222", cacheDirectory)
            val result =
                degradedSynchronizer.bootstrap(
                    degradedScope,
                    degradedBinding,
                    ConfigStartupMode.DEGRADED,
                )

            assertEquals(ConfigRegistrationStatus.NOT_READY, result.status)
            assertEquals("bootstrap_failed_no_cached_snapshot", result.reason)
            assertEquals(null, result.version)
            assertFalse(degradedBinding.initialized())
            assertEquals("default", degradedBinding.get())
            assertEquals(2, degradedScope.version())
            degradedSynchronizer.close()
        } finally {
            cacheDirectory.toFile().deleteRecursively()
            executors.forEach { executor -> executor.shutdownNow() }
        }
    }

    private object TestStringConfig :
        ConfigDefinition<String>(
            namespace = "plugin-config",
            key = "message",
            type = String::class.java,
            defaultValue = "default",
        )

    private class RecordingConfigSyncClient(
        private val operations: MutableList<String> = mutableListOf(),
        private val syncDefaultsHandler: RecordingConfigSyncClient.() -> SyncDefaultsResult = {
            SyncDefaultsResult(version = 0, createdKeys = emptyList())
        },
        private val getSnapshotHandler: RecordingConfigSyncClient.() -> SnapshotResult = {
            defaultSnapshotResponse(version = 1, value = "default")
        },
        private val getSnapshotIfNewerHandler:
            RecordingConfigSyncClient.(String, String, Long) -> SnapshotResult =
            { _, _, _ ->
                SnapshotResult(changed = false, version = 0, documents = emptyList())
            },
    ) : ConfigSyncClient {
        var syncDefaultCalls = 0
            private set

        var snapshotCalls = 0
            private set

        var getSnapshotIfNewerCalls = 0
            private set

        override fun getSnapshot(app: String, env: String): SnapshotResult {
            operations += "getSnapshot"
            snapshotCalls += 1
            return getSnapshotHandler()
        }

        override fun getSnapshotIfNewer(
            app: String,
            env: String,
            knownVersion: Long,
        ): SnapshotResult {
            operations += "getSnapshotIfNewer"
            getSnapshotIfNewerCalls += 1
            return getSnapshotIfNewerHandler(app, env, knownVersion)
        }

        override fun syncDefaults(
            app: String,
            env: String,
            defaults: List<ConfigDefaultData>,
        ): SyncDefaultsResult {
            operations += "syncDefaults"
            syncDefaultCalls += 1
            return syncDefaultsHandler()
        }

        override fun close() {}
    }

    private class RecordingConfigChangeListener(
        private val operations: MutableList<String> = mutableListOf(),
        private val onSubscribe: ((() -> Unit) -> Unit)? = null,
    ) : ConfigChangeListener {
        override fun start(natsUrl: String) {}

        override fun subscribe(app: String, env: String, onChangeReceived: () -> Unit) {
            operations += "subscribe"
            onSubscribe?.invoke(onChangeReceived)
        }

        override fun close() {}
    }

    private companion object {
        fun invokeRefreshTrackedScopes(synchronizer: ConfigScopeSynchronizer) {
            val method =
                ConfigScopeSynchronizer::class.java.getDeclaredMethod("refreshTrackedScopes")
            method.isAccessible = true
            method.invoke(synchronizer)
        }

        fun defaultSnapshotResponse(version: Long, value: String): SnapshotResult {
            return SnapshotResult(
                changed = true,
                version = version,
                documents =
                    listOf(
                        ConfigDocumentData(
                            namespace = TestStringConfig.namespace,
                            configKey = TestStringConfig.key,
                            contentJson = "\"$value\"",
                        )
                    ),
            )
        }
    }
}
