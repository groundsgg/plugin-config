package gg.grounds.config.nats

import gg.grounds.config.AppEnvKey
import io.nats.client.Connection
import io.nats.client.ConnectionListener
import io.nats.client.Dispatcher
import io.nats.client.Nats
import io.nats.client.Options
import java.nio.file.Files
import java.nio.file.Path
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
 *
 * These NATS events are best-effort refresh triggers only. Correctness still comes from the gRPC
 * reconcile path via `GetSnapshotIfNewer`, because the publisher is not part of the server's
 * transactional write path and consumers intentionally do not derive state from the event payload.
 * See `docs/adr/0001-nats-refresh-triggers-are-best-effort.md`.
 */
internal class NatsConfigListener(
    private val logger: Logger,
    private val connectionFactory: (Options) -> Connection = { options -> Nats.connect(options) },
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "config-nats-listener").apply { isDaemon = true }
        },
    initialReconnectDelayMs: Long = MIN_RECONNECT_DELAY_MS,
    private val maxReconnectDelayMs: Long = MAX_RECONNECT_DELAY_MS,
) : ConfigChangeListener {
    private val closed = AtomicBoolean(false)
    private val reconnectDelayMs = AtomicLong(initialReconnectDelayMs)
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
     * multiple times for different app/env pairs. The callback should reconcile through gRPC rather
     * than treating the NATS event or payload as authoritative config state.
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
            val builder = Options.Builder().server(natsUrl).connectionListener(::onConnectionEvent)
            // Present the projected SA-token (audience grounds-services) as the
            // NATS bearer for the auth-callout broker. Re-read per (re)connect for
            // kubelet rotation; skipped when absent (local/dev without the volume).
            val tokenFile = System.getenv("GROUNDS_TOKEN_FILE") ?: "/var/run/secrets/grounds/token"
            val tokenPath = Path.of(tokenFile)
            if (Files.exists(tokenPath)) {
                builder.tokenSupplier { Files.readString(tokenPath).trim().toCharArray() }
            }
            val conn = connectionFactory(builder.build())
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

    private fun onConnectionEvent(connection: Connection, event: ConnectionListener.Events) {
        if (connection !== this.connection) {
            return
        }
        when (event) {
            ConnectionListener.Events.CONNECTED -> {
                logger.info("NATS config listener connected successfully (url={})", natsUrl)
            }

            ConnectionListener.Events.DISCONNECTED -> {
                logger.warn("NATS config listener disconnected (url={})", natsUrl)
            }

            ConnectionListener.Events.RECONNECTED -> {
                resetBackoff()
                logger.info("NATS config listener reconnected successfully (url={})", natsUrl)
            }

            ConnectionListener.Events.CLOSED -> {
                this.connection = null
                dispatcher = null
                attachedSubscriptions.clear()
                logger.warn("NATS config listener closed (url={})", natsUrl)
                scheduleReconnect("connection_closed")
            }

            else -> Unit
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
        val nextDelay = (delayMs * 2).coerceAtMost(maxReconnectDelayMs)
        reconnectDelayMs.set(nextDelay)
    }

    private fun resetBackoff() {
        reconnectDelayMs.set(MIN_RECONNECT_DELAY_MS)
    }

    private fun closeConnection() {
        try {
            val currentConnection = connection
            val currentDispatcher = dispatcher
            dispatcher = null
            connection = null
            attachedSubscriptions.clear()
            currentDispatcher?.let { currentConnection?.closeDispatcher(it) }
            currentConnection?.close()
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
