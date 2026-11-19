package com.example.mmdvm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * 局域网内发现的一台 MMDVM 盒子。
 */
data class MmdvmDevice(
    val host: String,
    val type: String,
    val title: String? = null
) {
    val url: String get() = "http://$host"
}

/**
 * 简单的局域网扫描器：逐台探测 80 端口，再从首页 HTML 里识别固件类型。
 */
object LanScanner {

    const val HOST_COUNT = 254

    private const val CONNECT_TIMEOUT_MS = 350
    private const val READ_TIMEOUT_MS = 1500
    private const val MAX_READ_BYTES = 16 * 1024
    private const val CONCURRENCY = 48

    /** 当前手机在局域网中的 IPv4 地址，例如 192.168.1.23 */
    suspend fun localIpv4(): String? = withContext(Dispatchers.IO) {
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }

    /**
     * 扫描 subnetPrefix 下的 1~254 号地址，例如 "192.168.1"。
     */
    suspend fun scan(
        subnetPrefix: String,
        onProgress: suspend (scanned: Int) -> Unit,
        onFound: suspend (device: MmdvmDevice) -> Unit
    ) {
        coroutineScope {
            val permits = Semaphore(CONCURRENCY)
            val counter = AtomicInteger(0)
            (1..HOST_COUNT).map { host ->
                async(Dispatchers.IO) {
                    permits.withPermit {
                        probe("$subnetPrefix.$host")?.let { device ->
                            withContext(Dispatchers.Main) { onFound(device) }
                        }
                        val done = counter.incrementAndGet()
                        withContext(Dispatchers.Main) { onProgress(done) }
                    }
                }
            }.awaitAll()
        }
    }

    private fun probe(ip: String): MmdvmDevice? {
        var socket: Socket? = null
        return try {
            socket = Socket().apply {
                connect(InetSocketAddress(ip, 80), CONNECT_TIMEOUT_MS)
                soTimeout = READ_TIMEOUT_MS
            }
            val request = buildString {
                append("GET / HTTP/1.0\r\n")
                append("Host: $ip\r\n")
                append("User-Agent: MmdvmBox/1.0\r\n")
                append("Accept: text/html\r\n")
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.ISO_8859_1))
                flush()
            }

            val input = socket.getInputStream()
            val buffer = ByteArray(4096)
            val raw = java.io.ByteArrayOutputStream()
            while (raw.size() < MAX_READ_BYTES) {
                val read = input.read(buffer)
                if (read <= 0) break
                raw.write(buffer, 0, read)
            }

            val text = HttpText.decode(raw.toByteArray())
            val type = BoxDetector.classify(text)?.label ?: return null
            MmdvmDevice(host = ip, type = type, title = HttpText.titleOf(text))
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { socket?.close() }
        }
    }

}
