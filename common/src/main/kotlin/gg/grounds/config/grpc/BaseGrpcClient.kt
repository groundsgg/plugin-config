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
            channelBuilder.usePlaintext()
            return channelBuilder.build()
        }

        fun closeChannel(channel: ManagedChannel) {
            channel.shutdown()
            try {
                if (!channel.awaitTermination(3, TimeUnit.SECONDS)) {
                    channel.shutdownNow()
                    channel.awaitTermination(3, TimeUnit.SECONDS)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                channel.shutdownNow()
            }
        }
    }
}
