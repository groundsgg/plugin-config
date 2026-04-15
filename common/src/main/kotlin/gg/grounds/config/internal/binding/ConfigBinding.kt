package gg.grounds.config.internal.binding

import gg.grounds.config.ConfigDefinition
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import org.slf4j.LoggerFactory

/** Internal holder for a single config binding, managing the current value and change callbacks. */
internal class ConfigBinding<T : Any>(val definition: ConfigDefinition<T>) {
    private val logger = LoggerFactory.getLogger(ConfigBinding::class.java)
    private val state =
        AtomicReference(BindingState(value = definition.defaultValue, initialized = false))
    private val callbacks: CopyOnWriteArrayList<Consumer<T>> = CopyOnWriteArrayList()

    fun get(): T = state.get().value

    fun update(newValue: T) {
        setValue(newValue)
    }

    fun resetToDefault() {
        setValue(definition.defaultValue)
    }

    fun initialized(): Boolean = state.get().initialized

    private fun setValue(newValue: T) {
        val previousState = state.getAndSet(BindingState(value = newValue, initialized = true))
        val oldValue = previousState.value
        if (oldValue == newValue) {
            return
        }
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

    fun onChange(callback: Consumer<T>) {
        callbacks.add(callback)
    }

    private data class BindingState<T : Any>(val value: T, val initialized: Boolean)
}
