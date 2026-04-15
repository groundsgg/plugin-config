package gg.grounds.config.nats

import io.nats.client.Connection
import io.nats.client.ConnectionListener
import io.nats.client.Dispatcher
import java.lang.reflect.Proxy
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Delayed
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory

class NatsConfigListenerTest {
    @Test
    fun `closed connection schedules reconnect`() {
        val connections = CopyOnWriteArrayList<Connection>()
        val connectionListeners = CopyOnWriteArrayList<ConnectionListener>()
        val executor = RecordingScheduledExecutorService()
        val listener =
            NatsConfigListener(
                logger = LoggerFactory.getLogger("NatsConfigListenerReconnectTest"),
                connectionFactory = { options ->
                    createConnectionProxy().also { connection ->
                        connections += connection
                        connectionListeners += assertNotNull(options.connectionListener)
                    }
                },
                executor = executor,
                initialReconnectDelayMs = 1,
                maxReconnectDelayMs = 4,
            )

        try {
            listener.start("nats://localhost:4222")
            assertEquals(1, connections.size)

            emitConnectionEvent(
                connectionListener = connectionListeners.single(),
                connections.single(),
                event = ConnectionListener.Events.CLOSED,
            )

            assertEquals(1, executor.scheduledTasks.size)
            executor.runNext()
            assertEquals(2, connections.size)
        } finally {
            listener.close()
        }
    }

    @Test
    fun `disconnected connection relies on client reconnect`() {
        val connections = CopyOnWriteArrayList<Connection>()
        val connectionListeners = CopyOnWriteArrayList<ConnectionListener>()
        val executor = RecordingScheduledExecutorService()
        val listener =
            NatsConfigListener(
                logger = LoggerFactory.getLogger("NatsConfigListenerDisconnectTest"),
                connectionFactory = { options ->
                    createConnectionProxy().also { connection ->
                        connections += connection
                        connectionListeners += assertNotNull(options.connectionListener)
                    }
                },
                executor = executor,
                initialReconnectDelayMs = 1,
                maxReconnectDelayMs = 4,
            )

        try {
            listener.start("nats://localhost:4222")
            assertEquals(1, connections.size)

            emitConnectionEvent(
                connectionListener = connectionListeners.single(),
                connections.single(),
                event = ConnectionListener.Events.DISCONNECTED,
            )

            assertEquals(1, connections.size)
            assertTrue(executor.scheduledTasks.isEmpty())
        } finally {
            listener.close()
        }
    }

    private companion object {
        fun createConnectionProxy(): Connection {
            val dispatcher = createDispatcherProxy()
            return Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf(Connection::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "createDispatcher" -> dispatcher
                    "closeDispatcher" -> null
                    "close" -> null
                    "toString" -> "TestNatsConnection"
                    else -> defaultValue(method.returnType)
                }
            } as Connection
        }

        fun createDispatcherProxy(): Dispatcher =
            Proxy.newProxyInstance(
                Dispatcher::class.java.classLoader,
                arrayOf(Dispatcher::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "subscribe" -> null
                    "toString" -> "TestNatsDispatcher"
                    else -> defaultValue(method.returnType)
                }
            } as Dispatcher

        fun defaultValue(type: Class<*>): Any? =
            when (type) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Double.TYPE -> 0.0
                java.lang.Float.TYPE -> 0f
                java.lang.Short.TYPE -> 0.toShort()
                java.lang.Byte.TYPE -> 0.toByte()
                java.lang.Character.TYPE -> 0.toChar()
                else -> null
            }

        fun emitConnectionEvent(
            connectionListener: ConnectionListener,
            connection: Connection,
            event: ConnectionListener.Events,
        ) {
            connectionListener.connectionEvent(connection, event, System.currentTimeMillis(), null)
        }
    }

    private class RecordingScheduledExecutorService :
        AbstractExecutorService(), ScheduledExecutorService {
        val scheduledTasks = ArrayDeque<Runnable>()
        private var shutdown = false

        override fun shutdown() {
            shutdown = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shutdown = true
            val remainingTasks = scheduledTasks.toMutableList()
            scheduledTasks.clear()
            return remainingTasks
        }

        override fun isShutdown(): Boolean = shutdown

        override fun isTerminated(): Boolean = shutdown

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = shutdown

        override fun execute(command: Runnable) {
            command.run()
        }

        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            scheduledTasks += command
            return CompletedScheduledFuture<Unit>()
        }

        override fun <V : Any?> schedule(
            callable: Callable<V>,
            delay: Long,
            unit: TimeUnit,
        ): ScheduledFuture<V> {
            throw UnsupportedOperationException("Not used in tests")
        }

        override fun scheduleAtFixedRate(
            command: Runnable,
            initialDelay: Long,
            period: Long,
            unit: TimeUnit,
        ): ScheduledFuture<*> {
            throw UnsupportedOperationException("Not used in tests")
        }

        override fun scheduleWithFixedDelay(
            command: Runnable,
            initialDelay: Long,
            delay: Long,
            unit: TimeUnit,
        ): ScheduledFuture<*> {
            throw UnsupportedOperationException("Not used in tests")
        }

        fun runNext() {
            val nextTask = scheduledTasks.removeFirstOrNull()
            assertNotNull(nextTask)
            nextTask.run()
        }
    }

    private class CompletedScheduledFuture<V> : ScheduledFuture<V> {
        override fun getDelay(unit: TimeUnit): Long = 0

        override fun compareTo(other: Delayed): Int = 0

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false

        override fun isCancelled(): Boolean = false

        override fun isDone(): Boolean = true

        override fun get(): V? = null

        override fun get(timeout: Long, unit: TimeUnit): V? = null
    }
}
