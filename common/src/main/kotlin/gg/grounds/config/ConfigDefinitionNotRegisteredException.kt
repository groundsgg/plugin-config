package gg.grounds.config

class ConfigDefinitionNotRegisteredException(val definition: ConfigDefinition<*>) :
    IllegalStateException(
        "Config definition is not registered (namespace=${definition.namespace}, key=${definition.key}). " +
            "Call register(definition, app, env) before get() or onChange()."
    )
