package gg.grounds.config

import java.util.concurrent.CopyOnWriteArrayList

object ConfigManagerProvider {
    private val registeredManagers = CopyOnWriteArrayList<ConfigManager>()

    fun register(configManager: ConfigManager) {
        registeredManagers.addIfAbsent(configManager)
    }

    fun unregister(configManager: ConfigManager) {
        registeredManagers.remove(configManager)
    }

    fun get(): ConfigManager? = registeredManagers.lastOrNull()

    fun require(): ConfigManager {
        return get()
            ?: error("Config manager provider lookup failed (reason=manager_not_registered)")
    }
}
