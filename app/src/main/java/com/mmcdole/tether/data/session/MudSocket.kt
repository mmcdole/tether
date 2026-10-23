package com.mmcdole.tether.data.session

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class MudSocket(val input: InputStream, val output: OutputStream, private val onClose: () -> Unit) : Closeable {
    override fun close() = onClose()
}

/** Opens the byte streams for a connection. Blocking. Tests swap in in-memory streams. */
fun interface MudSocketFactory {
    fun open(host: String, port: Int, tls: Boolean): MudSocket
}

class TcpSocketFactory @Inject constructor() : MudSocketFactory {
    override fun open(host: String, port: Int, tls: Boolean): MudSocket {
        val tcp = Socket()
        try {
            tcp.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            tcp.tcpNoDelay = true
            // The kernel's keepalive starts after hours; MudConnection's IAC NOP covers the short NAT timeouts.
            tcp.keepAlive = true
            val socket = if (tls) startTls(tcp, host, port) else tcp
            return MudSocket(socket.getInputStream(), socket.getOutputStream(), socket::close)
        } catch (e: Exception) {
            tcp.close()
            throw e
        }
    }

    private fun startTls(tcp: Socket, host: String, port: Int): SSLSocket {
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(tcp, host, port, true) as SSLSocket
        ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        ssl.soTimeout = CONNECT_TIMEOUT_MS
        ssl.startHandshake()
        ssl.soTimeout = 0
        return ssl
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
    }
}
