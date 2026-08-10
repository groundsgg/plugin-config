package gg.grounds.config.client

/** One configuration document as the service holds it. */
data class ConfigDocumentData(
    val namespace: String,
    val configKey: String,
    val contentJson: String,
    val version: Long = 0,
)

/**
 * The answer to "what should I be holding?".
 *
 * [changed] is false only when the caller asked conditionally and nothing had moved; [version] is
 * then the version it already has, and [documents] is empty because there was nothing to send.
 */
data class SnapshotResult(
    val changed: Boolean,
    val version: Long,
    val documents: List<ConfigDocumentData>,
)

/** A document this app expects to exist, sent on startup. */
data class ConfigDefaultData(
    val namespace: String,
    val configKey: String,
    val defaultContentJson: String,
)

data class ConfigKeyData(val namespace: String, val configKey: String)

/** [createdKeys] holds only the documents that did not exist before. */
data class SyncDefaultsResult(val version: Long, val createdKeys: List<ConfigKeyData>)

/**
 * The service could not be asked, or answered something we cannot use. Distinct from a Kotlin
 * `IOException` only in carrying the status, and retryable for the same reason: the answer might be
 * different a moment later.
 */
class ConfigServiceException(message: String, val status: Int? = null) : RuntimeException(message)

internal interface ConfigSyncClient : AutoCloseable {
    fun getSnapshot(app: String, env: String): SnapshotResult

    fun getSnapshotIfNewer(app: String, env: String, knownVersion: Long): SnapshotResult

    fun syncDefaults(
        app: String,
        env: String,
        defaults: List<ConfigDefaultData>,
    ): SyncDefaultsResult
}
