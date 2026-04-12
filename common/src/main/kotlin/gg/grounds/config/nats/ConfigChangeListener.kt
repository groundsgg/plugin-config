package gg.grounds.config.nats

internal interface ConfigChangeListener : AutoCloseable {
    fun start(natsUrl: String)

    fun subscribe(app: String, env: String, onChangeReceived: () -> Unit)
}
