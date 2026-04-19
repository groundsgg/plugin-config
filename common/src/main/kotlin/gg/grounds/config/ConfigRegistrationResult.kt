package gg.grounds.config

/**
 * Controls how [ConfigManager.register] reacts when the initial sync/default bootstrap cannot
 * produce a ready typed value.
 */
enum class ConfigStartupMode {
    /** Fails registration immediately by throwing [ConfigRegistrationException]. */
    FAIL_CLOSED,

    /**
     * Allows registration to continue in degraded mode when a persisted cached snapshot can be
     * restored. Returns [ConfigRegistrationStatus.NOT_READY] when no cached snapshot is available.
     */
    DEGRADED,
}

/** Outcome returned by [ConfigManager.register]. */
enum class ConfigRegistrationStatus {
    /** The definition is registered and a current typed value is available. */
    READY,

    /** The definition is registered with a persisted cached snapshot. */
    DEGRADED,

    /** The definition is registered, but no typed value is available yet. */
    NOT_READY,

    /** Registration was skipped because the same definition was already registered. */
    ALREADY_REGISTERED,

    /** Registration was rejected because the config key conflicts with another definition. */
    REJECTED,
}

/**
 * Result of registering a [ConfigDefinition].
 *
 * `version` is present when the registration completed with a snapshot or cached snapshot. `reason`
 * contains a stable machine-readable reason for degraded or rejected outcomes.
 */
data class ConfigRegistrationResult(
    val status: ConfigRegistrationStatus,
    val version: Long? = null,
    val reason: String? = null,
) {
    /** Returns `true` when the definition can be used immediately via [ConfigManager.get]. */
    fun isUsable(): Boolean =
        status == ConfigRegistrationStatus.READY || status == ConfigRegistrationStatus.DEGRADED

    companion object {
        fun ready(version: Long? = null): ConfigRegistrationResult =
            ConfigRegistrationResult(status = ConfigRegistrationStatus.READY, version = version)

        fun degraded(version: Long? = null, reason: String): ConfigRegistrationResult =
            ConfigRegistrationResult(
                status = ConfigRegistrationStatus.DEGRADED,
                version = version,
                reason = reason,
            )

        fun notReady(reason: String): ConfigRegistrationResult =
            ConfigRegistrationResult(status = ConfigRegistrationStatus.NOT_READY, reason = reason)

        fun alreadyRegistered(reason: String): ConfigRegistrationResult =
            ConfigRegistrationResult(
                status = ConfigRegistrationStatus.ALREADY_REGISTERED,
                reason = reason,
            )

        fun rejected(reason: String): ConfigRegistrationResult =
            ConfigRegistrationResult(status = ConfigRegistrationStatus.REJECTED, reason = reason)
    }
}
