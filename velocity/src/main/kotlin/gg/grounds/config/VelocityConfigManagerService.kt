package gg.grounds.config

import com.velocitypowered.api.proxy.ProxyServer

typealias VelocityConfigManagerService = ConfigManagerService

object VelocityConfigManagerServices {
    private const val PLUGIN_ID = "plugin-config"

    fun get(proxy: ProxyServer): ConfigManager? {
        val pluginContainer = proxy.pluginManager.getPlugin(PLUGIN_ID).orElse(null) ?: return null
        val pluginInstance = pluginContainer.instance.orElse(null) ?: return null
        val service = pluginInstance as? ConfigManagerService ?: return null
        return service.configManager()
    }

    fun require(proxy: ProxyServer): ConfigManager {
        return get(proxy)
            ?: error(
                "Velocity config manager lookup failed (pluginId=$PLUGIN_ID, reason=service_not_available)"
            )
    }
}
