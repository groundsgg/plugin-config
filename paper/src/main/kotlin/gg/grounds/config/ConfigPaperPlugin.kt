package gg.grounds.config

import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin

class ConfigPaperPlugin : JavaPlugin() {
    private lateinit var configManager: ConfigManager
    private val environmentConfig = EnvironmentConfig()

    override fun onEnable() {
        configManager = ConfigManager(slF4JLogger)
        val serviceUrl = environmentConfig.serviceUrl()
        val natsUrl = environmentConfig.natsUrl()
        configManager.start(
            serviceUrl,
            natsUrl,
            dataFolder.toPath().resolve("runtime-config-cache"),
        )
        ConfigManagerProvider.register(configManager)
        server.servicesManager.register(
            ConfigManager::class.java,
            configManager,
            this,
            ServicePriority.Normal,
        )
        logger.info("Config plugin started (serviceUrl=$serviceUrl, natsUrl=$natsUrl)")
    }

    override fun onDisable() {
        if (this::configManager.isInitialized) {
            ConfigManagerProvider.unregister(configManager)
            configManager.close()
        }
    }
}
