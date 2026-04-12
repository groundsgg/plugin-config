package gg.grounds.config.internal.binding

import gg.grounds.config.ConfigDefinition
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigBindingTest {
    @Test
    fun `update notifies callbacks only when value changes`() {
        val binding = ConfigBinding(TestIntConfig)
        val callbacks = AtomicInteger(0)

        binding.onChange { callbacks.incrementAndGet() }

        binding.update(1)
        binding.update(1)
        binding.resetToDefault()

        assertTrue(binding.initialized())
        assertEquals(2, callbacks.get())
        assertEquals(0, binding.get())
    }

    private object TestIntConfig :
        ConfigDefinition<Int>(
            namespace = "plugin-config",
            key = "counter",
            type = Int::class.java,
            defaultValue = 0,
        )
}
