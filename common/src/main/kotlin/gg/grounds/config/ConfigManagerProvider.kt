package gg.grounds.config

import java.util.concurrent.atomic.AtomicReference

object ConfigManagerProvider {
    private val currentManager = AtomicReference<ConfigManager?>(null)

    fun register(configManager: ConfigManager) {
        val previousManager = currentManager.get()
        check(previousManager == null || previousManager === configManager) {
            "Config manager provider registration failed (reason=manager_already_registered)"
        }
        currentManager.compareAndSet(null, configManager)
    }

    fun unregister(configManager: ConfigManager) {
        currentManager.compareAndSet(configManager, null)
    }

    fun get(): ConfigManager? = currentManager.get()

    fun require(): ConfigManager {
        return get()
            ?: error("Config manager provider lookup failed (reason=manager_not_registered)")
    }
}
