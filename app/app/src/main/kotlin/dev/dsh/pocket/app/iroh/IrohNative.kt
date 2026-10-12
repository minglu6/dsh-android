package dev.dsh.pocket.app.iroh

import java.io.File

/**
 * JNI bridge to `libpocket_iroh.so` (Rust iroh client).
 * When the native library is absent (CI without NDK, or unsupported ABI),
 * [available] is false and the dialer falls back to WebSocket only.
 */
object IrohNative {
    @Volatile private var loaded = false
    @Volatile private var loadError: String? = null

    val available: Boolean get() {
        ensureLoaded()
        return loaded
    }

    val error: String? get() = loadError

    private fun ensureLoaded() {
        if (loaded || loadError != null) return
        synchronized(this) {
            if (loaded || loadError != null) return
            try {
                System.loadLibrary("pocket_iroh")
                loaded = true
            } catch (e: UnsatisfiedLinkError) {
                loadError = e.message
            }
        }
    }

    external fun endpointCreate(dataDir: String): Long
    external fun endpointId(ep: Long): String?
    external fun endpointConnect(ep: Long, peer: String, timeoutMs: Long): Long
    external fun endpointClose(ep: Long)
    external fun sessionSend(sess: Long, line: String): Int
    external fun sessionRecv(sess: Long, timeoutMs: Long): String?
    external fun sessionWaitPath(sess: Long, timeoutMs: Long): String?
    external fun sessionClose(sess: Long)
    external fun lastError(): String?

    fun requireAvailable() {
        if (!available) error("iroh native library unavailable: ${loadError ?: "not loaded"}")
    }

    fun defaultDataDir(filesDir: File): File = File(filesDir, "iroh").also { it.mkdirs() }
}
