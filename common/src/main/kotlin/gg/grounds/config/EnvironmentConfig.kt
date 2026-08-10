package gg.grounds.config

/**
 * Reads infrastructure configuration from environment variables for the config plugin. App and env
 * are not read from environment variables — they are set dynamically by consumer plugins when
 * registering config definitions.
 */
class EnvironmentConfig {
    /**
     * Where service-config answers. Both names on purpose: Argo does not order the plugin-jar fetch
     * against the manifest roll, so a jar that only understood the new variable would fail to start
     * on a pod still carrying the old one. `CONFIG_GRPC_TARGET` goes once every environment sets
     * `CONFIG_SERVICE_URL`.
     */
    fun serviceUrl(): String {
        return env("CONFIG_SERVICE_URL")
            ?: env("CONFIG_GRPC_TARGET")
            ?: error("Missing required environment variable CONFIG_SERVICE_URL")
    }

    fun natsUrl(): String {
        return requireEnv("CONFIG_NATS_URL")
    }

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private fun requireEnv(name: String): String {
        return env(name) ?: error("Missing required environment variable $name")
    }
}
