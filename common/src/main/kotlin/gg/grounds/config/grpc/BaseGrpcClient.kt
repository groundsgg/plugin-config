package gg.grounds.config.grpc

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import java.util.concurrent.TimeUnit

abstract class BaseGrpcClient(protected val channel: ManagedChannel) : AutoCloseable {
    override fun close() {
        closeChannel(channel)
    }

    companion object {
        fun createChannel(target: String): ManagedChannel {
            val channelBuilder = ManagedChannelBuilder.forTarget(target)
            if (usePlaintextTransport()) {
                channelBuilder.usePlaintext()
            } else {
                channelBuilder.useTransportSecurity()
            }
            return channelBuilder.build()
        }

        internal fun usePlaintextTransport(
            flagValue: String? = System.getProperty(GRPC_PLAINTEXT_PROPERTY)
        ): Boolean {
            // TODO: flip the default to TLS once secure config-service endpoints are the standard
            // deployment mode.
            return flagValue?.toBooleanStrictOrNull() ?: true
        }

        fun closeChannel(channel: ManagedChannel) {
            channel.shutdown()
            try {
                if (!channel.awaitTermination(3, TimeUnit.SECONDS)) {
                    channel.shutdownNow()
                    channel.awaitTermination(3, TimeUnit.SECONDS)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                channel.shutdownNow()
            }
        }

        private const val GRPC_PLAINTEXT_PROPERTY = "grounds.config.grpc.plaintext"
    }
}
