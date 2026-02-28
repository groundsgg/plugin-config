package gg.grounds.config.grpc

import gg.grounds.grpc.config.ConfigServiceGrpc
import gg.grounds.grpc.config.GetDocumentRequest
import gg.grounds.grpc.config.GetDocumentResponse
import gg.grounds.grpc.config.GetNamespaceSnapshotRequest
import gg.grounds.grpc.config.GetSnapshotIfNewerRequest
import gg.grounds.grpc.config.GetSnapshotRequest
import gg.grounds.grpc.config.GetSnapshotResponse
import gg.grounds.grpc.config.SyncDefaultsRequest
import gg.grounds.grpc.config.SyncDefaultsResponse
import io.grpc.ManagedChannel
import java.util.concurrent.TimeUnit

class GrpcConfigClient
private constructor(
    channel: ManagedChannel,
    private val stub: ConfigServiceGrpc.ConfigServiceBlockingStub,
) : BaseGrpcClient(channel) {
    fun getSnapshot(app: String, env: String): GetSnapshotResponse {
        val request = GetSnapshotRequest.newBuilder().setApp(app).setEnv(env).build()
        return stub.withDeadlineAfter(TIMEOUT_SECONDS, TimeUnit.SECONDS).getSnapshot(request)
    }

    fun getSnapshotIfNewer(app: String, env: String, knownVersion: Long): GetSnapshotResponse {
        val request =
            GetSnapshotIfNewerRequest.newBuilder()
                .setApp(app)
                .setEnv(env)
                .setKnownVersion(knownVersion)
                .build()
        return stub.withDeadlineAfter(TIMEOUT_SECONDS, TimeUnit.SECONDS).getSnapshotIfNewer(request)
    }

    fun getNamespaceSnapshot(app: String, env: String, namespace: String): GetSnapshotResponse {
        val request =
            GetNamespaceSnapshotRequest.newBuilder()
                .setApp(app)
                .setEnv(env)
                .setNamespace(namespace)
                .build()
        return stub
            .withDeadlineAfter(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .getNamespaceSnapshot(request)
    }

    fun getDocument(
        app: String,
        env: String,
        namespace: String,
        configKey: String,
    ): GetDocumentResponse {
        val request =
            GetDocumentRequest.newBuilder()
                .setApp(app)
                .setEnv(env)
                .setNamespace(namespace)
                .setConfigKey(configKey)
                .build()
        return stub.withDeadlineAfter(TIMEOUT_SECONDS, TimeUnit.SECONDS).getDocument(request)
    }

    fun syncDefaults(request: SyncDefaultsRequest): SyncDefaultsResponse {
        return stub.withDeadlineAfter(SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS).syncDefaults(request)
    }

    companion object {
        private const val TIMEOUT_SECONDS = 5L
        private const val SYNC_TIMEOUT_SECONDS = 15L

        fun create(target: String): GrpcConfigClient {
            val channel = createChannel(target)
            val stub = ConfigServiceGrpc.newBlockingStub(channel)
            return GrpcConfigClient(channel, stub)
        }
    }
}
