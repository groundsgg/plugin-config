package gg.grounds.config

import gg.grounds.config.grpc.ConfigSyncClient
import gg.grounds.config.internal.scope.ConfigScopeRegistry
import gg.grounds.config.internal.sync.ConfigScopeSynchronizer
import gg.grounds.config.nats.ConfigChangeListener
import gg.grounds.grpc.config.ConfigDocument
import gg.grounds.grpc.config.GetSnapshotResponse
import gg.grounds.grpc.config.SyncDefaultsRequest
import gg.grounds.grpc.config.SyncDefaultsResponse
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory

class ConfigManagerTest {
    @Test
    fun `register retries cleanly after fail closed bootstrap failure`() {
        val logger = LoggerFactory.getLogger("ConfigManagerTest")
        val manager = ConfigManager(logger)
        val client = RetryableRecordingConfigSyncClient()
        val synchronizer =
            ConfigScopeSynchronizer(
                logger = logger,
                grpcClientFactory = { client },
                natsListenerFactory = { NoopConfigChangeListener() },
                sleepMillis = {},
            )
        val scopeRegistryField = ConfigManager::class.java.getDeclaredField("scopeRegistry")
        scopeRegistryField.isAccessible = true
        scopeRegistryField.set(manager, ConfigScopeRegistry())
        val synchronizerField = ConfigManager::class.java.getDeclaredField("scopeSynchronizer")
        synchronizerField.isAccessible = true
        synchronizerField.set(manager, synchronizer)

        try {
            manager.start("dns:///config", "nats://localhost:4222")

            assertFailsWith<ConfigRegistrationException> {
                manager.register(TestStringConfig, "test-app", "dev", ConfigStartupMode.FAIL_CLOSED)
            }

            assertFailsWith<ConfigDefinitionNotRegisteredException> { manager[TestStringConfig] }

            val retryResult =
                manager.register(TestStringConfig, "test-app", "dev", ConfigStartupMode.FAIL_CLOSED)

            assertEquals(ConfigRegistrationStatus.READY, retryResult.status)
            assertTrue(retryResult.isUsable())
            assertEquals("ready", manager[TestStringConfig])
            assertEquals(2, client.syncDefaultsCalls)
            assertEquals(4, client.getSnapshotCalls)
        } finally {
            manager.close()
        }
    }

    private object TestStringConfig :
        ConfigDefinition<String>(
            namespace = "plugin-config",
            key = "message",
            type = String::class.java,
            defaultValue = "default",
        )

    private class RetryableRecordingConfigSyncClient : ConfigSyncClient {
        var syncDefaultsCalls = 0
            private set

        var getSnapshotCalls = 0
            private set

        override fun getSnapshot(app: String, env: String): GetSnapshotResponse {
            getSnapshotCalls += 1
            if (getSnapshotCalls <= 3) {
                throw StatusRuntimeException(
                    Status.UNAVAILABLE.withDescription("snapshot unavailable")
                )
            }
            return GetSnapshotResponse.newBuilder()
                .setChanged(true)
                .setVersion(7)
                .addDocuments(
                    ConfigDocument.newBuilder()
                        .setNamespace(TestStringConfig.namespace)
                        .setConfigKey(TestStringConfig.key)
                        .setContentJson("\"ready\"")
                        .build()
                )
                .build()
        }

        override fun getSnapshotIfNewer(
            app: String,
            env: String,
            knownVersion: Long,
        ): GetSnapshotResponse = GetSnapshotResponse.getDefaultInstance()

        override fun syncDefaults(request: SyncDefaultsRequest): SyncDefaultsResponse {
            syncDefaultsCalls += 1
            val createdKeys =
                request.defaultsList.map { defaultConfig ->
                    gg.grounds.grpc.config.ConfigDocumentKey.newBuilder()
                        .setNamespace(defaultConfig.namespace)
                        .setConfigKey(defaultConfig.configKey)
                        .build()
                }
            return SyncDefaultsResponse.newBuilder().addAllCreatedKeys(createdKeys).build()
        }

        override fun close() {}
    }

    private class NoopConfigChangeListener : ConfigChangeListener {
        override fun start(natsUrl: String) {}

        override fun subscribe(app: String, env: String, onChangeReceived: () -> Unit) {}

        override fun close() {}
    }
}
