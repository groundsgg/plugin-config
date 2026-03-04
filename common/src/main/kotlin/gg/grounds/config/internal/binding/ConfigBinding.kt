package gg.grounds.config.internal.binding

import gg.grounds.config.ConfigDefinition
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import org.slf4j.LoggerFactory

/** Internal holder for a single config binding, managing the current value and change callbacks. */
internal class ConfigBinding<T : Any>(val definition: ConfigDefinition<T>) {
    private val logger = LoggerFactory.getLogger(ConfigBinding::class.java)
    private val currentValue: AtomicReference<T> = AtomicReference(definition.defaultValue)
    private val callbacks: CopyOnWriteArrayList<Consumer<T>> = CopyOnWriteArrayList()

    fun get(): T = currentValue.get()

    fun update(newValue: T) {
        val oldValue = currentValue.getAndSet(newValue)
        if (oldValue != newValue) {
            for (callback in callbacks) {
                try {
                    callback.accept(newValue)
                } catch (error: Exception) {
                    logger.warn(
                        "Config callback execution failed (namespace={}, key={}, callbackType={})",
                        definition.namespace,
                        definition.key,
                        callback.javaClass.name,
                        error,
                    )
                }
            }
        }
    }

    fun onChange(callback: Consumer<T>) {
        callbacks.add(callback)
    }
}
