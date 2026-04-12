package gg.grounds.config.grpc

import gg.grounds.grpc.config.GetSnapshotResponse
import gg.grounds.grpc.config.SyncDefaultsRequest
import gg.grounds.grpc.config.SyncDefaultsResponse

internal interface ConfigSyncClient : AutoCloseable {
    fun getSnapshot(app: String, env: String): GetSnapshotResponse

    fun getSnapshotIfNewer(app: String, env: String, knownVersion: Long): GetSnapshotResponse

    fun syncDefaults(request: SyncDefaultsRequest): SyncDefaultsResponse
}
