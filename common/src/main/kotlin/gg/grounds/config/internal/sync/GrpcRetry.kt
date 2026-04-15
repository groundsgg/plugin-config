package gg.grounds.config.internal.sync

import gg.grounds.config.internal.scope.AppEnvScope
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import org.slf4j.Logger

internal fun <T> executeRetryableGrpcCall(
    logger: Logger,
    scope: AppEnvScope,
    operation: String,
    maxAttempts: Int,
    sleepMillis: (Long) -> Unit,
    block: () -> T,
): T {
    var attempt = 1
    var retryDelayMs = INITIAL_GRPC_RETRY_DELAY_MS
    while (true) {
        try {
            return block()
        } catch (error: Exception) {
            if (!isRetryableGrpcFailure(error) || attempt >= maxAttempts) {
                throw error
            }
            logger.warn(
                "Config gRPC call failed (app={}, env={}, operation={}, attempt={}, maxAttempts={}, retryInMs={}, reason={})",
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
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_GRPC_RETRY_DELAY_MS)
        }
    }
}

private fun isRetryableGrpcFailure(error: Exception): Boolean {
    return error is StatusException || error is StatusRuntimeException
}

private const val INITIAL_GRPC_RETRY_DELAY_MS = 250L
private const val MAX_GRPC_RETRY_DELAY_MS = 1000L
