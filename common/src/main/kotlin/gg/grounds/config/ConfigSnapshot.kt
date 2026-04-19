package gg.grounds.config

/** Holds a versioned snapshot of all config documents for an (app, env) pair. */
data class ConfigSnapshot(val version: Long, val documents: Map<ConfigKey, String>) {
    companion object {
        fun empty(): ConfigSnapshot = ConfigSnapshot(version = 0, documents = emptyMap())
    }
}

/** Composite key identifying a single config document within a snapshot. */
data class ConfigKey(val namespace: String, val configKey: String)

/** Composite key identifying an application–environment pair. */
data class AppEnvKey(val app: String, val env: String)
