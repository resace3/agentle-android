package dev.agentle.core.oauth

import dev.agentle.core.common.LogRecord
import dev.agentle.core.common.LogSink
import dev.agentle.core.common.Logger
import dev.agentle.core.common.Severity
import java.net.InetAddress
import java.net.Socket
import java.util.Collections

/** A plain HTTP/1.1 client on raw sockets, so tests control the method, target, Host header and line endings. */
internal object RawHttp {
    data class Reply(val status: Int, val headers: Map<String, String>, val body: String) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    fun send(port: Int, raw: String, readTimeoutMs: Int = 5_000): Reply? = Socket(LOOPBACK, port).use { socket ->
        socket.soTimeout = readTimeoutMs
        socket.getOutputStream().apply {
            write(raw.toByteArray(Charsets.ISO_8859_1))
            flush()
        }
        parse(socket.getInputStream().readAllBytes())
    }

    fun get(port: Int, target: String, host: String? = "127.0.0.1:$port", method: String = "GET", extraHeaders: String = ""): Reply? {
        val hostLine = host?.let { "Host: $it\r\n" }.orEmpty()
        return send(port, "$method $target HTTP/1.1\r\n${hostLine}User-Agent: test\r\n$extraHeaders\r\n")
    }

    fun parse(bytes: ByteArray): Reply? {
        if (bytes.isEmpty()) return null
        val text = String(bytes, Charsets.UTF_8)
        val headEnd = text.indexOf("\r\n\r\n")
        val headLines = text.substring(0, headEnd).split("\r\n")
        val status = headLines.first().split(' ')[1].toInt()
        val headers = headLines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        return Reply(status, headers, text.substring(headEnd + 4))
    }
}

/** Captures every sanitized log record, from any thread. */
internal class CapturingSink : LogSink {
    val records: MutableList<LogRecord> = Collections.synchronizedList(mutableListOf())

    override fun write(record: LogRecord) {
        records += record
    }

    fun text(): String = synchronized(records) {
        records.joinToString("\n") { "${it.component} ${it.message} ${it.errorCode} ${it.fields}" }
    }

    fun logger(): Logger = Logger(listOf(this), { 0L }, minSeverity = Severity.VERBOSE)
}
