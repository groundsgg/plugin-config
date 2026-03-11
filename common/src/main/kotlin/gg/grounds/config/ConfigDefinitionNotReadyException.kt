package gg.grounds.config

class ConfigDefinitionNotReadyException(val definition: ConfigDefinition<*>) :
    IllegalStateException(
        "Config definition is not ready (namespace=${definition.namespace}, key=${definition.key}). " +
            "Wait for the initial snapshot to load before calling get()."
    )
