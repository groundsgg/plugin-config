package gg.grounds.config.internal.cache

import gg.grounds.config.ConfigSnapshot
import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.Logger
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonMapperBuilder

internal interface ConfigSnapshotCache {
    fun load(app: String, env: String): ConfigSnapshot?

    fun save(app: String, env: String, snapshot: ConfigSnapshot)

    companion object {
        fun noop(): ConfigSnapshotCache = NoopConfigSnapshotCache

        fun create(logger: Logger, cacheDirectory: Path?): ConfigSnapshotCache {
            return if (cacheDirectory == null) {
                NoopConfigSnapshotCache
            } else {
                FileConfigSnapshotCache(logger, cacheDirectory)
            }
        }
    }
}

private object NoopConfigSnapshotCache : ConfigSnapshotCache {
    override fun load(app: String, env: String): ConfigSnapshot? = null

    override fun save(app: String, env: String, snapshot: ConfigSnapshot) = Unit
}

private class FileConfigSnapshotCache(
    private val logger: Logger,
    private val cacheDirectory: Path,
    private val objectMapper: ObjectMapper = jacksonMapperBuilder().build(),
) : ConfigSnapshotCache {
    override fun load(app: String, env: String): ConfigSnapshot? {
        val file = snapshotFile(app, env)
        if (!Files.exists(file)) {
            return null
        }
        return try {
            Files.newBufferedReader(file).use { reader ->
                objectMapper.readValue(reader, CachedSnapshot::class.java).toSnapshot()
            }
        } catch (error: Exception) {
            logger.warn(
                "Config snapshot cache load failed (app={}, env={}, path={}, reason={})",
                app,
                env,
                file,
                error.message ?: error::class.java.simpleName,
            )
            null
        }
    }

    override fun save(app: String, env: String, snapshot: ConfigSnapshot) {
        val file = snapshotFile(app, env)
        try {
            Files.createDirectories(cacheDirectory)
            Files.newBufferedWriter(file).use { writer ->
                objectMapper.writeValue(writer, CachedSnapshot.fromSnapshot(snapshot))
            }
        } catch (error: Exception) {
            logger.warn(
                "Config snapshot cache save failed (app={}, env={}, path={}, reason={})",
                app,
                env,
                file,
                error.message ?: error::class.java.simpleName,
            )
        }
    }

    private fun snapshotFile(app: String, env: String): Path =
        cacheDirectory.resolve("${app}__${env}.json")

    private data class CachedSnapshot(
        val version: Long = 0,
        val documents: List<CachedDocument> = emptyList(),
    ) {
        fun toSnapshot(): ConfigSnapshot =
            ConfigSnapshot(
                version = version,
                documents =
                    documents.associate { document -> document.toKey() to document.contentJson },
            )

        companion object {
            fun fromSnapshot(snapshot: ConfigSnapshot): CachedSnapshot =
                CachedSnapshot(
                    version = snapshot.version,
                    documents =
                        snapshot.documents.map { (key, contentJson) ->
                            CachedDocument(
                                namespace = key.namespace,
                                configKey = key.configKey,
                                contentJson = contentJson,
                            )
                        },
                )
        }
    }

    private data class CachedDocument(
        val namespace: String = "",
        val configKey: String = "",
        val contentJson: String = "",
    ) {
        fun toKey() = gg.grounds.config.ConfigKey(namespace, configKey)
    }
}
