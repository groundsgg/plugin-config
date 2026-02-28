package gg.grounds.config

/**
 * Base class for defining a typed runtime configuration document.
 *
 * Consumer plugins create one subclass per config document, typically as a Kotlin object:
 * ```
 * data class ChatFilterRules(
 *     val maxWarnings: Int = 3,
 *     val muteDurationSeconds: Long = 300,
 *     val blockedWords: List<String> = emptyList(),
 * )
 *
 * object ChatFilterRulesConfig : ConfigDefinition<ChatFilterRules>(
 *     namespace = "chatfilter",
 *     key = "rules",
 *     type = ChatFilterRules::class.java,
 *     defaultValue = ChatFilterRules(),
 * )
 * ```
 */
abstract class ConfigDefinition<T : Any>(
    val namespace: String,
    val key: String,
    val type: Class<T>,
    val defaultValue: T,
)
