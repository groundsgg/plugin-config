package gg.grounds.config

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Dependency
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import gg.grounds.BuildInfo
import java.nio.file.Path
import org.slf4j.Logger

@Plugin(
    id = "plugin-config",
    name = "Grounds Plugin Config",
    version = BuildInfo.VERSION,
    description = "Runtime configuration management for Velocity",
    authors = ["Grounds Development Team and contributors"],
    url = "https://github.com/groundsgg/plugin-config",
    dependencies = [Dependency(id = "plugin-grounds-runtime")],
)
class ConfigVelocityPlugin
@Inject
constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
    @param:DataDirectory private val dataDirectory: Path,
) : VelocityConfigManagerService {
    private val configManager = ConfigManager(logger)
    private val environmentConfig = EnvironmentConfig()

    init {
        logger.info("Initialized plugin (plugin=plugin-config, version={})", BuildInfo.VERSION)
    }

    @Subscribe
    fun onInitialize(event: ProxyInitializeEvent) {
        val serviceUrl = environmentConfig.serviceUrl()
        val natsUrl = environmentConfig.natsUrl()
        configManager.start(serviceUrl, natsUrl, dataDirectory.resolve("runtime-config-cache"))
        ConfigManagerProvider.register(configManager)
        logger.info(
            "Config plugin started successfully (serviceUrl={}, natsUrl={}, serviceResolver=plugin_manager)",
            serviceUrl,
            natsUrl,
        )
    }

    @Subscribe
    fun onShutdown(event: ProxyShutdownEvent) {
        ConfigManagerProvider.unregister(configManager)
        configManager.close()
    }

    /** Returns the [ConfigManager] for other plugins to register and access configs. */
    override fun configManager(): ConfigManager = configManager
}
