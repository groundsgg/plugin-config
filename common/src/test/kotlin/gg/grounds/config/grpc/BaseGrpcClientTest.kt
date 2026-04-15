package gg.grounds.config.grpc

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BaseGrpcClientTest {
    @Test
    fun `usePlaintextTransport defaults to plaintext when flag is missing`() {
        assertTrue(BaseGrpcClient.usePlaintextTransport(flagValue = null))
    }

    @Test
    fun `usePlaintextTransport disables plaintext when flag is false`() {
        assertFalse(BaseGrpcClient.usePlaintextTransport(flagValue = "false"))
    }

    @Test
    fun `usePlaintextTransport keeps plaintext when flag is true`() {
        assertTrue(BaseGrpcClient.usePlaintextTransport(flagValue = "true"))
    }
}
