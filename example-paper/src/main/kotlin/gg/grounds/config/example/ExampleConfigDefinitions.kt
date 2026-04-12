package gg.grounds.config.example

import gg.grounds.config.ConfigDefinition

/**
 * Typed config definitions used by [ExamplePaperPlugin].
 *
 * Each object extends [ConfigDefinition] and describes:
 * - backend namespace/key
 * - expected Kotlin type
 * - local default value used when a document is missing
 */

// -- Lobby app configs ----------------------------------------------------------

/** Example config payload for the "lobby/settings" document. */
data class LobbySettings(
    val maxPlayers: Int = 20,
    val motd: String = "Welcome to the lobby!",
    val allowedGameModes: List<String> = listOf("survival", "adventure"),
)

/** Definition for `namespace=lobby`, `key=settings`. */
object LobbySettingsConfig :
    ConfigDefinition<LobbySettings>(
        namespace = "lobby",
        key = "settings",
        type = LobbySettings::class.java,
        defaultValue = LobbySettings(),
    )

// -- Game-service app configs ---------------------------------------------------

/** Example config payload for the "game/rules" document. */
data class GameRules(
    val roundDurationSeconds: Long = 600,
    val isFriendlyFireEnabled: Boolean = false,
    val maxTeamSize: Int = 4,
)

/** Definition for `namespace=game`, `key=rules`. */
object GameRulesConfig :
    ConfigDefinition<GameRules>(
        namespace = "game",
        key = "rules",
        type = GameRules::class.java,
        defaultValue = GameRules(),
    )

// -- Unregistered config used for fail-fast demo --------------------------------

/** Deliberately never registered; used to demonstrate fail-fast access behavior. */
data class UnregisteredExample(val enabled: Boolean = true, val note: String = "demo")

/** Definition used for unregistered access demo in [ExamplePaperPlugin]. */
object UnregisteredExampleConfig :
    ConfigDefinition<UnregisteredExample>(
        namespace = "example",
        key = "unregistered",
        type = UnregisteredExample::class.java,
        defaultValue = UnregisteredExample(),
    )
