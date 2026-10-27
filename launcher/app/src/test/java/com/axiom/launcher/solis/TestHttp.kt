package com.axiom.launcher.solis

import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** A tiny HTTP/1.1 server on 127.0.0.1 for tests: one handler answers every request. */
class TestHttp(private val handler: (Req) -> Res) {
    class Req(val method: String, val path: String, val query: String, val headers: Map<String, String>, val body: String)
    class Res(val code: Int, val body: String = "", val type: String = "application/json", val headers: Map<String, String> = emptyMap())

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    val input = sock.getInputStream()
                    val h = StringBuilder()
                    while (!h.endsWith("\r\n\r\n")) { val c = input.read(); if (c < 0) break; h.append(c.toChar()) }
                    val lines = h.lines()
                    val (method, target) = lines.first().split(' ').let { it[0] to it[1] }
                    val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
                    val len = headers["content-length"]?.toIntOrNull() ?: 0
                    val b = ByteArray(len); var off = 0
                    while (off < len) { val n = input.read(b, off, len - off); if (n < 0) break; off += n }
                    val res = handler(Req(method, target.substringBefore('?'), target.substringAfter('?', ""), headers, b.decodeToString()))
                    val out = res.body.toByteArray()
                    val extra = res.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
                    sock.getOutputStream().apply {
                        write("HTTP/1.1 ${res.code} X\r\nContent-Type: ${res.type}\r\n${extra}Content-Length: ${out.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(out); flush()
                    }
                }
                runCatching { sock.close() }
            }
        }
    }

    fun close() = server.close()
}
