package gg.grounds.config.client

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The conditional read, which is the whole reason this client is not just four GETs.
 *
 * A snapshot that cannot be read must never look like a snapshot with no documents: the applier
 * would treat every binding as missing. So failures are raised for the synchronizer to retry, and
 * only a 304 is allowed to produce an empty document list.
 */
class HttpConfigClientTest {

    @Test
    fun `a first read sends no If-None-Match and gets the documents`() {
        val seen = CopyOnWriteArrayList<String?>()
        withServer({ exchange ->
            seen.add(exchange.requestHeaders.getFirst("If-None-Match"))
            200 to
                """{"version":7,"documents":[{"namespace":"motd","configKey":"network","contentJson":"\"hi\"","version":7}]}"""
        }) { client ->
            val result = client.getSnapshot("velocity", "stage")

            assertTrue(result.changed)
            assertEquals(7, result.version)
            assertEquals("motd", result.documents.single().namespace)
            assertEquals("\"hi\"", result.documents.single().contentJson)
        }
        assertNull(seen.single())
    }

    @Test
    fun `a conditional read sends the version it holds as a quoted etag`() {
        val seen = CopyOnWriteArrayList<String?>()
        withServer({ exchange ->
            seen.add(exchange.requestHeaders.getFirst("If-None-Match"))
            exchange.responseHeaders.add("ETag", "\"7\"")
            304 to ""
        }) { client ->
            val result = client.getSnapshotIfNewer("velocity", "stage", 7)

            assertFalse(result.changed)
            assertEquals(7, result.version)
            // Empty because nothing was sent, not because the app has no config — which is only
            // safe to report alongside changed=false.
            assertTrue(result.documents.isEmpty())
        }
        assertEquals("\"7\"", seen.single())
    }

    @Test
    fun `a 304 without a usable ETag falls back to the version we asked with`() {
        withServer({ _ -> 304 to "" }) { client ->
            assertEquals(4, client.getSnapshotIfNewer("velocity", "stage", 4).version)
        }
    }

    @Test
    fun `a server error is raised, never reported as an empty snapshot`() {
        withServer({ _ -> 503 to "" }) { client ->
            val error = assertFailsWith<ConfigServiceException> { client.getSnapshot("v", "stage") }
            assertEquals(503, error.status)
        }
    }

    @Test
    fun `syncDefaults reports only what it created`() {
        withServer({ _ ->
            200 to """{"version":9,"createdKeys":[{"namespace":"motd","configKey":"network"}]}"""
        }) { client ->
            val result =
                client.syncDefaults(
                    "velocity",
                    "stage",
                    listOf(ConfigDefaultData("motd", "network", "\"hi\"")),
                )

            assertEquals(9, result.version)
            assertEquals(ConfigKeyData("motd", "network"), result.createdKeys.single())
        }
    }

    private fun withServer(
        handler: (HttpExchange) -> Pair<Int, String>,
        block: (HttpConfigClient) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.readBytes()
            val (status, body) = handler(exchange)
            val bytes = body.toByteArray()
            if (bytes.isEmpty()) {
                exchange.sendResponseHeaders(status, -1)
            } else {
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        server.start()
        val client = HttpConfigClient.create("127.0.0.1:${server.address.port}")
        try {
            block(client)
        } finally {
            client.close()
            server.stop(0)
        }
    }
}
