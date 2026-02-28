package gg.grounds.config

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/** Internal holder for a single config binding, managing the current value and change callbacks. */
internal class ConfigBinding<T : Any>(val definition: ConfigDefinition<T>) {
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
                    // Swallow callback errors to avoid breaking the update chain
                }
            }
        }
    }

    fun onChange(callback: Consumer<T>) {
        callbacks.add(callback)
    }
}
