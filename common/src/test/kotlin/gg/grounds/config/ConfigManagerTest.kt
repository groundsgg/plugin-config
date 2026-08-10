package gg.grounds.config

import gg.grounds.config.client.ConfigDefaultData
import gg.grounds.config.client.ConfigDocumentData
import gg.grounds.config.client.ConfigKeyData
import gg.grounds.config.client.ConfigServiceException
import gg.grounds.config.client.ConfigSyncClient
import gg.grounds.config.client.SnapshotResult
import gg.grounds.config.client.SyncDefaultsResult
import gg.grounds.config.internal.sync.ConfigScopeSynchronizer
import gg.grounds.config.nats.ConfigChangeListener
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory

class ConfigManagerTest {
    @Test
    fun `register retries cleanly after fail closed bootstrap failure`() {
        val logger = LoggerFactory.getLogger("ConfigManagerTest")
        val client = RetryableRecordingConfigSyncClient()
        val synchronizer =
            ConfigScopeSynchronizer(
                logger = logger,
                configClientFactory = { client },
                natsListenerFactory = { NoopConfigChangeListener() },
                sleepMillis = {},
            )
        val manager = ConfigManager(logger, scopeSynchronizerFactory = { synchronizer })

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

        override fun getSnapshot(app: String, env: String): SnapshotResult {
            getSnapshotCalls += 1
            if (getSnapshotCalls <= 3) {
                // A 503 is retryable: the answer may be different a moment later.
                throw ConfigServiceException("snapshot unavailable", 503)
            }
            return SnapshotResult(
                changed = true,
                version = 7,
                documents =
                    listOf(
                        ConfigDocumentData(
                            namespace = TestStringConfig.namespace,
                            configKey = TestStringConfig.key,
                            contentJson = "\"ready\"",
                        )
                    ),
            )
        }

        override fun getSnapshotIfNewer(
            app: String,
            env: String,
            knownVersion: Long,
        ): SnapshotResult = SnapshotResult(changed = false, version = 0, documents = emptyList())

        override fun syncDefaults(
            app: String,
            env: String,
            defaults: List<ConfigDefaultData>,
        ): SyncDefaultsResult {
            syncDefaultsCalls += 1
            val createdKeys = defaults.map { ConfigKeyData(it.namespace, it.configKey) }
            return SyncDefaultsResult(version = 0, createdKeys = createdKeys)
        }

        override fun close() {}
    }

    private class NoopConfigChangeListener : ConfigChangeListener {
        override fun start(natsUrl: String) {}

        override fun subscribe(app: String, env: String, onChangeReceived: () -> Unit) {}

        override fun close() {}
    }
}
