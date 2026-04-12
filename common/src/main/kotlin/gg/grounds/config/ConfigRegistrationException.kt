package gg.grounds.config

class ConfigRegistrationException(
    val definition: ConfigDefinition<*>,
    val app: String,
    val env: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
