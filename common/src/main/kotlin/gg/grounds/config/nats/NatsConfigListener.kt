package gg.grounds.config.nats

import gg.grounds.config.AppEnvKey
import io.nats.client.Connection
import io.nats.client.Dispatcher
import io.nats.client.Nats
import io.nats.client.Options
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.Logger

/**
 * Manages a single NATS connection with dynamic per-app/env subscriptions. Each subscription
 * listens to `config.{app}.{env}.changed` and invokes the corresponding callback when a change
 * event arrives.
 */
internal class NatsConfigListener(private val logger: Logger) : ConfigChangeListener {
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "config-nats-listener").apply { isDaemon = true }
        }
    private val closed = AtomicBoolean(false)
    private val reconnectDelayMs = AtomicLong(MIN_RECONNECT_DELAY_MS)
    private val subscriptions = ConcurrentHashMap<AppEnvKey, SubscriptionEntry>()
    private val attachedSubscriptions = ConcurrentHashMap.newKeySet<AppEnvKey>()
    private var connection: Connection? = null
    private var dispatcher: Dispatcher? = null
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var natsUrl: String = ""

    /** Connects to the NATS server. Must be called before [subscribe]. */
    override fun start(natsUrl: String) {
        this.natsUrl = natsUrl
        closed.set(false)
        reconnectFuture?.cancel(false)
        closeConnection()
        connect()
    }

    /**
     * Subscribes to config change events for the given app/env pair. The [onChangeReceived]
     * callback is invoked when a change arrives on `config.{app}.{env}.changed`. Safe to call
     * multiple times for different app/env pairs.
     */
    override fun subscribe(app: String, env: String, onChangeReceived: () -> Unit) {
        val key = AppEnvKey(app, env)
        val entry = SubscriptionEntry(app, env, onChangeReceived)
        val previousEntry = subscriptions.putIfAbsent(key, entry)
        if (previousEntry != null) {
            logger.debug("NATS subscription already exists (app={}, env={})", app, env)
            return
        }
        val disp = dispatcher
        if (disp != null) {
            attachSubscription(disp, key, entry)
        }
    }

    private fun connect() {
        if (closed.get()) {
            return
        }
        try {
            val options = Options.Builder().server(natsUrl).build()
            val conn = Nats.connect(options)
            connection = conn
            val disp = conn.createDispatcher()
            attachedSubscriptions.clear()
            dispatcher = disp
            for ((key, entry) in subscriptions.entries) {
                attachSubscription(disp, key, entry)
            }
            resetBackoff()
            logger.info(
                "NATS config listener connected (url={}, subscriptions={})",
                natsUrl,
                subscriptions.size,
            )
        } catch (error: Exception) {
            logger.warn(
                "Failed to connect NATS config listener (url={}, error={})",
                natsUrl,
                error.message,
            )
            scheduleReconnect(error.message ?: "unknown")
        }
    }

    private fun attachSubscription(
        dispatcher: Dispatcher,
        key: AppEnvKey,
        entry: SubscriptionEntry,
    ) {
        if (!attachedSubscriptions.add(key)) {
            logger.debug("NATS subscription attach skipped (app={}, env={})", entry.app, entry.env)
            return
        }
        val subject = "config.${entry.app}.${entry.env}.changed"
        dispatcher.subscribe(subject) { message ->
            logger.debug(
                "Config change event received (subject={}, data={})",
                message.subject,
                String(message.data, Charsets.UTF_8),
            )
            entry.onChangeReceived()
        }
        logger.info("Subscribed to config changes (subject={})", subject)
    }

    private fun scheduleReconnect(reason: String) {
        if (closed.get()) {
            return
        }
        val delayMs = reconnectDelayMs.get()
        logger.warn(
            "NATS config listener scheduling reconnect (reason={}, retryInMs={})",
            reason,
            delayMs,
        )
        reconnectFuture?.cancel(false)
        reconnectFuture = executor.schedule({ connect() }, delayMs, TimeUnit.MILLISECONDS)
        val nextDelay = (delayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
        reconnectDelayMs.set(nextDelay)
    }

    private fun resetBackoff() {
        reconnectDelayMs.set(MIN_RECONNECT_DELAY_MS)
    }

    private fun closeConnection() {
        try {
            dispatcher?.let { connection?.closeDispatcher(it) }
            dispatcher = null
            connection?.close()
            connection = null
            attachedSubscriptions.clear()
        } catch (error: Exception) {
            logger.warn("Error closing NATS connection (error={})", error.message)
        }
    }

    override fun close() {
        closed.set(true)
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        subscriptions.clear()
        closeConnection()
        executor.shutdownNow()
    }

    private data class SubscriptionEntry(
        val app: String,
        val env: String,
        val onChangeReceived: () -> Unit,
    )

    companion object {
        private const val MIN_RECONNECT_DELAY_MS = 1000L
        private const val MAX_RECONNECT_DELAY_MS = 30000L
    }
}
