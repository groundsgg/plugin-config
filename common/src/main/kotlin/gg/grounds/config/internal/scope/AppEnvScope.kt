package gg.grounds.config.internal.scope

import gg.grounds.config.ConfigKey
import gg.grounds.config.internal.binding.ConfigBinding
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Internal scope holding per-(app, env) state: version tracking and config bindings. */
internal class AppEnvScope(val app: String, val env: String) {
    private val currentVersion = AtomicLong(0)
    private val bindings = ConcurrentHashMap<ConfigKey, ConfigBinding<*>>()
    private val subscriptionStarted = AtomicBoolean(false)
    private val refreshLock = Any()

    fun putBindingIfAbsent(key: ConfigKey, binding: ConfigBinding<*>): ConfigBinding<*>? {
        return bindings.putIfAbsent(key, binding)
    }

    fun binding(key: ConfigKey): ConfigBinding<*>? = bindings[key]

    fun removeBinding(key: ConfigKey, binding: ConfigBinding<*>): Boolean {
        return bindings.remove(key, binding)
    }

    fun bindingsSnapshot(): Map<ConfigKey, ConfigBinding<*>> = bindings.toMap()

    fun version(): Long = currentVersion.get()

    fun setVersion(version: Long): Long = currentVersion.getAndSet(version)

    fun markSubscriptionStarted(): Boolean = subscriptionStarted.compareAndSet(false, true)

    fun hasUninitializedBindings(): Boolean = bindings.values.any { !it.initialized() }

    fun withRefreshLock(block: () -> Unit) {
        synchronized(refreshLock) { block() }
    }
}
