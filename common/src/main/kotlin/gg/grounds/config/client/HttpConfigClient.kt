package gg.grounds.config.client

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import tools.jackson.databind.ObjectMapper

/**
 * service-config over HTTP.
 *
 * The conditional read is an `ETag` round trip rather than a second operation: a snapshot answers
 * with its version in `ETag`, the caller sends it back as `If-None-Match`, and an unchanged
 * snapshot comes back as `304` with no body. That is the same question `GetSnapshotIfNewer` asked,
 * spelled the way HTTP already spells it.
 *
 * Failures are raised, not swallowed. The synchronizer above retries them — config that cannot be
 * read is not config that is empty, and returning an empty snapshot would wipe every binding.
 */
internal class HttpConfigClient
private constructor(private val baseUri: URI, private val http: HttpClient) : ConfigSyncClient {

    private val json = ObjectMapper()

    override fun getSnapshot(app: String, env: String): SnapshotResult =
        readSnapshot(snapshotPath(app, env), knownVersion = null)

    override fun getSnapshotIfNewer(app: String, env: String, knownVersion: Long): SnapshotResult =
        readSnapshot(snapshotPath(app, env), knownVersion)

    override fun syncDefaults(
        app: String,
        env: String,
        defaults: List<ConfigDefaultData>,
    ): SyncDefaultsResult {
        val body =
            json.writeValueAsString(
                mapOf(
                    "defaults" to
                        defaults.map {
                            mapOf(
                                "namespace" to it.namespace,
                                "configKey" to it.configKey,
                                "defaultContentJson" to it.defaultContentJson,
                            )
                        }
                )
            )
        val response =
            exchange(
                HttpRequest.newBuilder(baseUri.resolve("/v1/config/apps/$app/envs/$env/defaults"))
                    .timeout(SYNC_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
            )
        if (response.statusCode() !in 200..299) {
            throw ConfigServiceException(
                "syncDefaults answered ${response.statusCode()}",
                response.statusCode(),
            )
        }
        val node = json.readTree(response.body())
        val createdKeys = mutableListOf<ConfigKeyData>()
        node.get("createdKeys")?.forEach { entry ->
            createdKeys.add(
                ConfigKeyData(
                    namespace = entry.get("namespace").asString(),
                    configKey = entry.get("configKey").asString(),
                )
            )
        }
        return SyncDefaultsResult(
            version = node.get("version")?.asLong() ?: 0,
            createdKeys = createdKeys,
        )
    }

    private fun readSnapshot(path: String, knownVersion: Long?): SnapshotResult {
        val builder =
            HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .GET()
        knownVersion?.let { builder.header("If-None-Match", "\"$it\"") }

        val response = exchange(builder)
        if (response.statusCode() == 304) {
            // Nothing moved. The version we already hold is the current one; the service repeats it
            // in the ETag, and we fall back to what we asked with if it does not.
            val version = response.headers().firstValue("ETag").orElse(null)?.let(::parseETag)
            return SnapshotResult(
                changed = false,
                version = version ?: knownVersion ?: 0,
                documents = emptyList(),
            )
        }
        if (response.statusCode() !in 200..299) {
            throw ConfigServiceException(
                "snapshot answered ${response.statusCode()}",
                response.statusCode(),
            )
        }

        val node = json.readTree(response.body())
        val documents = mutableListOf<ConfigDocumentData>()
        node.get("documents")?.forEach { entry ->
            documents.add(
                ConfigDocumentData(
                    namespace = entry.get("namespace").asString(),
                    configKey = entry.get("configKey").asString(),
                    contentJson = entry.get("contentJson").asString(),
                    version = entry.get("version")?.asLong() ?: 0,
                )
            )
        }
        return SnapshotResult(
            changed = true,
            version = node.get("version")?.asLong() ?: 0,
            documents = documents,
        )
    }

    private fun exchange(builder: HttpRequest.Builder): HttpResponse<String> {
        currentToken()?.let { builder.header("Authorization", "Bearer $it") }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    override fun close() {
        http.close()
    }

    companion object {
        private val TIMEOUT: Duration = Duration.ofSeconds(5)
        private val SYNC_TIMEOUT: Duration = Duration.ofSeconds(15)
        private const val DEFAULT_TOKEN_PATH = "/var/run/secrets/grounds/token"

        fun create(target: String): HttpConfigClient {
            // The chart injects the address with no scheme; java.net.http throws parsing that
            // directly, so default to http.
            val baseUri = URI.create(if (target.contains("://")) target else "http://$target")
            return HttpConfigClient(baseUri, HttpClient.newHttpClient())
        }

        private fun snapshotPath(app: String, env: String) =
            "/v1/config/apps/$app/envs/$env/snapshot"

        /** Quotes and a weak marker are the tag's spelling, not part of the version. */
        internal fun parseETag(header: String): Long? =
            header.trim().removePrefix("W/").trim('"').toLongOrNull()

        /**
         * Re-read per call, never cached: a projected token the kubelet rotates would expire under
         * a long-running Velocity process, and config refreshes would quietly start failing auth.
         */
        private fun currentToken(): String? {
            val path = Path.of(System.getenv("GROUNDS_TOKEN_FILE") ?: DEFAULT_TOKEN_PATH)
            return try {
                if (Files.exists(path)) Files.readString(path).trim().ifEmpty { null } else null
            } catch (_: Exception) {
                null
            }
        }
    }
}
