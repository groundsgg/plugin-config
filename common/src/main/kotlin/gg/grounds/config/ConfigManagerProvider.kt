package gg.grounds.config

import java.util.concurrent.atomic.AtomicReference

object ConfigManagerProvider {
    private val registeredManager = AtomicReference<ConfigManager?>(null)

    fun register(configManager: ConfigManager) {
        while (true) {
            val currentManager = registeredManager.get()
            if (currentManager == configManager) {
                return
            }
            check(currentManager == null) {
                "Config manager provider registration failed (reason=manager_already_registered)"
            }
            if (registeredManager.compareAndSet(null, configManager)) {
                return
            }
        }
    }

    fun unregister(configManager: ConfigManager) {
        registeredManager.compareAndSet(configManager, null)
    }

    fun get(): ConfigManager? = registeredManager.get()

    fun require(): ConfigManager {
        return get()
            ?: error("Config manager provider lookup failed (reason=manager_not_registered)")
    }
}
