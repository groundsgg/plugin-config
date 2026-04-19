package gg.grounds.config

/**
 * Reads infrastructure configuration from environment variables for the config plugin. App and env
 * are not read from environment variables — they are set dynamically by consumer plugins when
 * registering config definitions.
 */
class EnvironmentConfig {
    fun grpcTarget(): String {
        return requireEnv("CONFIG_GRPC_TARGET")
    }

    fun natsUrl(): String {
        return requireEnv("CONFIG_NATS_URL")
    }

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private fun requireEnv(name: String): String {
        return env(name) ?: error("Missing required environment variable $name")
    }
}
