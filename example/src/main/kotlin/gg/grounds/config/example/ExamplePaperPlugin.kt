package gg.grounds.config.example

import gg.grounds.config.ConfigDefinitionNotRegisteredException
import gg.grounds.config.ConfigManager
import org.bukkit.plugin.java.JavaPlugin

/**
 * Demonstrates end-to-end usage of [ConfigManager] from a Paper plugin.
 *
 * Startup flow:
 * 1. Resolve [ConfigManager] from Paper's [ServicesManager]
 * 2. Register typed config definitions for one or more (app, env) scopes
 * 3. Read initial values with [ConfigManager.get]
 * 4. Subscribe to live updates with [ConfigManager.onChange]
 * 5. Show fail-fast behavior when a definition was not registered
 *
 * Important contract:
 * - Always call `register(definition, app, env)` before `get(definition)` or `onChange(definition,
 *   callback)` for that definition.
 * - Unregistered access throws [ConfigDefinitionNotRegisteredException].
 */
class ExamplePaperPlugin : JavaPlugin() {
    override fun onEnable() {
        // ConfigManager is exposed by plugin-config via Bukkit services.
        val configManager =
            server.servicesManager.load(ConfigManager::class.java)
                ?: error("ConfigManager service not available — is plugin-config installed?")

        // Register configs from two different apps in the same environment.
        // The first registration for each (app, env) creates an internal scope that syncs
        // defaults, loads a snapshot, and subscribes to NATS updates.
        configManager.register(LobbySettingsConfig, app = "lobby", env = "prod")
        configManager.register(GameRulesConfig, app = "game-service", env = "prod")

        // Read current values immediately after registration.
        val lobbySettings = configManager.get(LobbySettingsConfig)
        logger.info(
            "Lobby settings loaded (maxPlayers=${lobbySettings.maxPlayers}, motd=${lobbySettings.motd})"
        )
        val gameRules = configManager.get(GameRulesConfig)
        logger.info(
            "Game rules loaded (roundDurationSeconds=${gameRules.roundDurationSeconds}, " +
                "isFriendlyFireEnabled=${gameRules.isFriendlyFireEnabled})"
        )

        // Listen for live config updates pushed through NATS.
        configManager.onChange(LobbySettingsConfig) { settings ->
            logger.info(
                "Lobby settings changed (maxPlayers=${settings.maxPlayers}, motd=${settings.motd})"
            )
        }
        configManager.onChange(GameRulesConfig) { rules ->
            logger.info(
                "Game rules changed (roundDurationSeconds=${rules.roundDurationSeconds}, " +
                    "isFriendlyFireEnabled=${rules.isFriendlyFireEnabled})"
            )
        }

        // Demonstrate fail-fast behavior for definitions that were never registered.
        try {
            configManager.get(UnregisteredExampleConfig)
        } catch (error: ConfigDefinitionNotRegisteredException) {
            logger.info(
                "Unregistered config lookup failed as expected " +
                    "(namespace=${error.definition.namespace}, key=${error.definition.key}, " +
                    "error=${error.message})"
            )
        }

        logger.info("Example config plugin enabled")
    }

    override fun onDisable() {
        logger.info("Example config plugin disabled")
    }
}
