package gg.grounds.config.example

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Dependency
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.ProxyServer
import gg.grounds.config.ConfigDefinitionNotRegisteredException
import gg.grounds.config.ConfigManager
import gg.grounds.config.ConfigManagerService
import gg.grounds.config.ConfigRegistrationResult
import gg.grounds.config.ConfigStartupMode
import org.slf4j.Logger

/**
 * Demonstrates end-to-end usage of the config client from a Velocity plugin.
 *
 * The flow mirrors `ExamplePaperPlugin`, but resolves [ConfigManager] by looking up the
 * `plugin-config` plugin and casting it to [ConfigManagerService].
 */
@Plugin(
    id = "plugin-config-example-velocity",
    name = "Grounds Config Example Velocity",
    version = "local-SNAPSHOT",
    description = "Example Velocity integration for plugin-config",
    authors = ["Grounds Development Team and contributors"],
    dependencies = [Dependency(id = "plugin-config")],
)
class ExampleVelocityPlugin
@Inject
constructor(private val proxy: ProxyServer, private val logger: Logger) {
    @Subscribe
    fun onInitialize(event: ProxyInitializeEvent) {
        val configManager = resolveConfigManager()

        val lobbyRegistration =
            configManager.register(
                LobbySettingsConfig,
                app = "lobby",
                env = "prod",
                startupMode = ConfigStartupMode.FAIL_CLOSED,
            )
        logRegistration(
            app = "lobby",
            env = "prod",
            namespace = LobbySettingsConfig.namespace,
            key = LobbySettingsConfig.key,
            result = lobbyRegistration,
        )

        val gameRulesRegistration =
            configManager.register(
                GameRulesConfig,
                app = "game-service",
                env = "prod",
                startupMode = ConfigStartupMode.DEGRADED,
            )
        logRegistration(
            app = "game-service",
            env = "prod",
            namespace = GameRulesConfig.namespace,
            key = GameRulesConfig.key,
            result = gameRulesRegistration,
        )

        val lobbySettings = configManager[LobbySettingsConfig]
        logger.info(
            "Velocity lobby settings loaded (maxPlayers={}, motd={})",
            lobbySettings.maxPlayers,
            lobbySettings.motd,
        )

        if (gameRulesRegistration.isUsable()) {
            val gameRules = configManager[GameRulesConfig]
            logger.info(
                "Velocity game rules loaded (roundDurationSeconds={}, isFriendlyFireEnabled={})",
                gameRules.roundDurationSeconds,
                gameRules.isFriendlyFireEnabled,
            )
        } else {
            logger.warn(
                "Velocity game rules not ready at startup (status={}, reason={})",
                gameRulesRegistration.status,
                gameRulesRegistration.reason,
            )
        }

        configManager.onChange(LobbySettingsConfig) { settings ->
            logger.info(
                "Velocity lobby settings changed (maxPlayers={}, motd={})",
                settings.maxPlayers,
                settings.motd,
            )
        }
        configManager.onChange(GameRulesConfig) { rules ->
            logger.info(
                "Velocity game rules changed (roundDurationSeconds={}, isFriendlyFireEnabled={})",
                rules.roundDurationSeconds,
                rules.isFriendlyFireEnabled,
            )
        }

        try {
            configManager[UnregisteredExampleConfig]
        } catch (error: ConfigDefinitionNotRegisteredException) {
            logger.info(
                "Velocity unregistered config lookup failed as expected (namespace={}, key={}, error={})",
                error.definition.namespace,
                error.definition.key,
                error.message,
            )
        }

        logger.info(
            "Velocity example config plugin enabled (pluginId=plugin-config-example-velocity)"
        )
    }

    @Subscribe
    fun onShutdown(event: ProxyShutdownEvent) {
        logger.info(
            "Velocity example config plugin disabled (pluginId=plugin-config-example-velocity)"
        )
    }

    private fun resolveConfigManager(): ConfigManager {
        val pluginContainer =
            proxy.pluginManager.getPlugin("plugin-config").orElse(null)
                ?: error(
                    "Velocity config manager lookup failed (pluginId=plugin-config, reason=plugin_not_installed)"
                )
        val pluginInstance =
            pluginContainer.instance.orElse(null)
                ?: error(
                    "Velocity config manager lookup failed (pluginId=plugin-config, reason=plugin_instance_not_available)"
                )
        val service =
            pluginInstance as? ConfigManagerService
                ?: error(
                    "Velocity config manager lookup failed (pluginId=plugin-config, reason=service_not_available)"
                )
        return service.configManager()
    }

    private fun logRegistration(
        app: String,
        env: String,
        namespace: String,
        key: String,
        result: ConfigRegistrationResult,
    ) {
        logger.info(
            "Velocity config registration completed (app={}, env={}, namespace={}, key={}, status={}, version={}, reason={})",
            app,
            env,
            namespace,
            key,
            result.status,
            result.version,
            result.reason,
        )
    }
}
