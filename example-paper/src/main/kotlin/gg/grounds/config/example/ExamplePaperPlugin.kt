package gg.grounds.config.example

import gg.grounds.config.ConfigDefinitionNotRegisteredException
import gg.grounds.config.ConfigManager
import gg.grounds.config.ConfigRegistrationResult
import gg.grounds.config.ConfigStartupMode
import org.bukkit.plugin.java.JavaPlugin

/**
 * Demonstrates end-to-end usage of [ConfigManager] from a Paper plugin.
 *
 * This example shows both startup strategies:
 * - `FAIL_CLOSED` for config that must be available before the plugin can continue startup
 * - `DEGRADED` for config that may fall back to a persisted cached snapshot during startup
 *
 * Startup flow:
 * 1. Resolve [ConfigManager] from Paper's [org.bukkit.plugin.ServicesManager]
 * 2. Register typed config definitions for one or more (app, env) scopes
 * 3. Inspect [ConfigRegistrationResult] for registrations that may continue in degraded mode
 * 4. Read initial values with indexed access on [ConfigManager]
 * 5. Subscribe to live updates with [ConfigManager.onChange]
 * 6. Show fail-fast behavior when a definition was not registered
 *
 * Important contract:
 * - Always register a config before reading it or subscribing to changes for it.
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
        //
        // startupMode controls what happens when the initial bootstrap cannot produce a ready
        // typed value:
        // - FAIL_CLOSED throws ConfigRegistrationException and should be used for mandatory config.
        // - DEGRADED returns a ConfigRegistrationResult so the plugin can decide whether degraded
        //   startup is acceptable when a persisted cached snapshot was restored.
        val lobbyRegistration =
            configManager.register(
                LobbySettingsConfig,
                app = "lobby",
                env = "prod",
                startupMode = ConfigStartupMode.FAIL_CLOSED,
            )
        val gameRulesRegistration =
            configManager.register(
                GameRulesConfig,
                app = "game-service",
                env = "prod",
                startupMode = ConfigStartupMode.DEGRADED,
            )

        // Read current values immediately after registration.
        val lobbySettings = configManager[LobbySettingsConfig]
        logger.info(
            "Lobby settings loaded (maxPlayers=${lobbySettings.maxPlayers}, motd=${lobbySettings.motd})"
        )
        logRegistration(
            app = "lobby",
            env = "prod",
            namespace = LobbySettingsConfig.namespace,
            key = LobbySettingsConfig.key,
            result = lobbyRegistration,
        )
        logRegistration(
            app = "game-service",
            env = "prod",
            namespace = GameRulesConfig.namespace,
            key = GameRulesConfig.key,
            result = gameRulesRegistration,
        )
        if (gameRulesRegistration.isUsable()) {
            val gameRules = configManager[GameRulesConfig]
            logger.info(
                "Game rules loaded (roundDurationSeconds=${gameRules.roundDurationSeconds}, " +
                    "isFriendlyFireEnabled=${gameRules.isFriendlyFireEnabled})"
            )
        } else {
            logger.warning(
                "Game rules not ready at startup " +
                    "(status=${gameRulesRegistration.status}, reason=${gameRulesRegistration.reason})"
            )
        }

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
            configManager[UnregisteredExampleConfig]
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

    private fun logRegistration(
        app: String,
        env: String,
        namespace: String,
        key: String,
        result: ConfigRegistrationResult,
    ) {
        logger.info(
            "Config registration completed " +
                "(app=$app, env=$env, namespace=$namespace, key=$key, " +
                "status=${result.status}, version=${result.version}, reason=${result.reason})"
        )
    }
}
