package gg.grounds.config.internal.sync

import gg.grounds.config.client.ConfigServiceException
import gg.grounds.config.internal.scope.AppEnvScope
import java.io.IOException
import org.slf4j.Logger

internal fun <T> executeRetryableCall(
    logger: Logger,
    scope: AppEnvScope,
    operation: String,
    maxAttempts: Int,
    sleepMillis: (Long) -> Unit,
    block: () -> T,
): T {
    var attempt = 1
    var retryDelayMs = INITIAL_RETRY_DELAY_MS
    while (true) {
        try {
            return block()
        } catch (error: Exception) {
            if (!isRetryableFailure(error) || attempt >= maxAttempts) {
                throw error
            }
            logger.warn(
                "Config call failed (app={}, env={}, operation={}, attempt={}, maxAttempts={}, retryInMs={}, reason={})",
                scope.app,
                scope.env,
                operation,
                attempt,
                maxAttempts,
                retryDelayMs,
                error.message ?: error::class.java.simpleName,
            )
            try {
                sleepMillis(retryDelayMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            }
            attempt += 1
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
        }
    }
}

/**
 * A call that did not land, as opposed to one the service answered with a refusal. Both a dropped
 * connection and a 5xx may be different a moment later; anything else — a malformed request, a 403
 * — will not be, and retrying it only delays the error.
 */
private fun isRetryableFailure(error: Exception): Boolean =
    error is IOException ||
        (error is ConfigServiceException && (error.status == null || error.status >= 500))

private const val INITIAL_RETRY_DELAY_MS = 250L
private const val MAX_RETRY_DELAY_MS = 1000L
