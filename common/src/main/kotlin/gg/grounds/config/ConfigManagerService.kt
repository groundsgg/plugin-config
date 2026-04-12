package gg.grounds.config

/** Exposes a [ConfigManager] instance to other plugins on the same platform runtime. */
interface ConfigManagerService {
    fun configManager(): ConfigManager
}
