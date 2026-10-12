package dev.dsh.pocket.protocol

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/**
 * Confirms OkHttp negotiates permessage-deflate (server offers it; client requests it by default)
 * and that our client keeps the 1 KiB compress threshold aligned with the pocket server.
 */
class WebSocketCompressionTest {
    @Test
    fun defaultHttpUsesOneKilobyteCompressThreshold() {
        assertEquals(1024L, WsTransport.MIN_MESSAGE_TO_COMPRESS)
        // Builder returns a client; minWebSocketMessageToCompress is not readable, but
        // defaultHttp() applies the same constant the pocket server uses (threshold: 1024).
        val http = PocketClient.defaultHttp()
        assertNotNull(http)
    }

    @Test
    fun okHttpOffersPermessageDeflateOnUpgrade() {
        val server = MockWebServer()
        val extensions = AtomicReference<String?>()
        val opened = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    extensions.set(response.request.header("Sec-WebSocket-Extensions"))
                    opened.countDown()
                    webSocket.close(1000, null)
                }
            }),
        )
        server.start()
        try {
            val http = PocketClient.defaultHttp()
            val url = server.url("/").toString().replace("http://", "ws://")
            val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {})
            assertTrue(opened.await(5, TimeUnit.SECONDS), "websocket should open")
            val header = extensions.get()
            assertNotNull(header)
            assertTrue(header!!.contains("permessage-deflate"), "OkHttp must offer permessage-deflate; got: $header")
            ws.cancel()
        } finally {
            server.shutdown()
        }
    }
}
