package dev.dsh.pocket.protocol

import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.IOException
import java.net.*
import kotlin.concurrent.thread
import kotlin.test.*

/**
 * Regression: on a phone with a system HTTP proxy, OkHttp sent the WS upgrade to the proxy, which hung up
 * -> "transport: unexpected end of stream on http://100.106.128.87:7690/...". The client must connect directly.
 */
class ProxyBypassTest {
    @Test fun ignoresSystemProxy() = runBlocking {
        // A "proxy" that accepts and immediately closes, like a proxy that cannot route to the tailnet.
        val bad = ServerSocket(0)
        thread(isDaemon = true) { while (!bad.isClosed) runCatching { bad.accept().close() } }
        val prev = ProxySelector.getDefault()
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI?) = listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", bad.localPort)))
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
        })
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val id = PocketJson.parseToJsonElement(text)["id"].long()
                webSocket.send("""{"id":$id,"ok":true,"result":{"ops":["prompt"],"device":{"id":"d1"}}}""")
            }
            override fun onOpen(webSocket: WebSocket, response: Response) {}
        }))
        server.start()
        try {
            val c = PocketClient()
            val hello = c.connect("ws://127.0.0.1:${server.port}", Auth.Token("t"))
            assertEquals(setOf("prompt"), hello.ops)
            c.close()
        } finally {
            ProxySelector.setDefault(prev); runCatching { server.shutdown() }; bad.close()
        }
    }
}
