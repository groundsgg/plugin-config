package gg.grounds.config

import io.grpc.LoadBalancerRegistry
import io.grpc.NameResolverRegistry
import io.grpc.internal.DnsNameResolverProvider
import io.grpc.internal.PickFirstLoadBalancerProvider
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin

class ConfigPaperPlugin : JavaPlugin() {
    private lateinit var configManager: ConfigManager
    private val environmentConfig = EnvironmentConfig()

    override fun onEnable() {
        configManager = ConfigManager(slF4JLogger)
        registerProviders()
        val grpcTarget = environmentConfig.grpcTarget()
        val natsUrl = environmentConfig.natsUrl()
        configManager.start(
            grpcTarget,
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
        logger.info("Config plugin started (grpcTarget=$grpcTarget, natsUrl=$natsUrl)")
    }

    override fun onDisable() {
        if (this::configManager.isInitialized) {
            ConfigManagerProvider.unregister(configManager)
            configManager.close()
        }
    }

    private fun registerProviders() {
        NameResolverRegistry.getDefaultRegistry().register(DnsNameResolverProvider())
        LoadBalancerRegistry.getDefaultRegistry().register(PickFirstLoadBalancerProvider())
    }
}
