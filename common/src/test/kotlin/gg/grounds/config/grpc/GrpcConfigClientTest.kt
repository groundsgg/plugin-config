package gg.grounds.config.grpc

import gg.grounds.grpc.config.ConfigDocument
import gg.grounds.grpc.config.ConfigServiceGrpc
import gg.grounds.grpc.config.GetDocumentResponse
import io.grpc.CallOptions
import io.grpc.ClientCall
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GrpcConfigClientTest {
    @Test
    fun `getDocument returns response when document exists`() {
        val response =
            GetDocumentResponse.newBuilder()
                .setDocument(
                    ConfigDocument.newBuilder()
                        .setNamespace("gameplay")
                        .setConfigKey("welcome")
                        .setContentJson("\"value\"")
                        .build()
                )
                .build()
        val client = createClient(response = response)

        try {
            val document = client.getDocument("test-app", "dev", "gameplay", "welcome")

            assertNotNull(document)
            assertEquals("gameplay", document.document.namespace)
            assertEquals("welcome", document.document.configKey)
            assertEquals("\"value\"", document.document.contentJson)
        } finally {
            client.close()
        }
    }

    @Test
    fun `getDocument returns null when server responds not found`() {
        val client = createClient(status = Status.NOT_FOUND.withDescription("document missing"))

        try {
            val document = client.getDocument("test-app", "dev", "gameplay", "welcome")

            assertNull(document)
        } finally {
            client.close()
        }
    }

    private companion object {
        fun createClient(
            response: GetDocumentResponse? = null,
            status: Status = Status.OK,
        ): GrpcConfigClient {
            val channel = FakeManagedChannel(response = response, status = status)
            val stub = ConfigServiceGrpc.newBlockingStub(channel)
            val constructor =
                GrpcConfigClient::class
                    .java
                    .getDeclaredConstructor(
                        ManagedChannel::class.java,
                        ConfigServiceGrpc.ConfigServiceBlockingStub::class.java,
                    )
            constructor.isAccessible = true
            return constructor.newInstance(channel, stub)
        }
    }

    private class FakeManagedChannel(
        private val response: GetDocumentResponse?,
        private val status: Status,
    ) : ManagedChannel() {
        private var shutdown = false

        override fun authority(): String = "test-authority"

        override fun shutdown(): ManagedChannel {
            shutdown = true
            return this
        }

        override fun isShutdown(): Boolean = shutdown

        override fun isTerminated(): Boolean = shutdown

        override fun shutdownNow(): ManagedChannel {
            shutdown = true
            return this
        }

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true

        override fun <RequestT : Any?, ResponseT : Any?> newCall(
            methodDescriptor: MethodDescriptor<RequestT, ResponseT>,
            callOptions: CallOptions,
        ): ClientCall<RequestT, ResponseT> {
            return FakeClientCall(methodDescriptor, response, status)
        }
    }

    private class FakeClientCall<RequestT, ResponseT>(
        private val methodDescriptor: MethodDescriptor<RequestT, ResponseT>,
        private val response: GetDocumentResponse?,
        private val status: Status,
    ) : ClientCall<RequestT, ResponseT>() {
        private lateinit var listener: Listener<ResponseT>

        override fun start(responseListener: Listener<ResponseT>, headers: Metadata) {
            listener = responseListener
            listener.onHeaders(Metadata())
        }

        override fun request(numMessages: Int) = Unit

        override fun cancel(message: String?, cause: Throwable?) = Unit

        override fun halfClose() {
            if (
                methodDescriptor.fullMethodName !=
                    ConfigServiceGrpc.getGetDocumentMethod().fullMethodName
            ) {
                listener.onClose(Status.UNIMPLEMENTED, Metadata())
                return
            }
            if (status.isOk && response != null) {
                @Suppress("UNCHECKED_CAST") listener.onMessage(response as ResponseT)
            }
            listener.onClose(status, Metadata())
        }

        override fun sendMessage(message: RequestT) = Unit
    }
}
