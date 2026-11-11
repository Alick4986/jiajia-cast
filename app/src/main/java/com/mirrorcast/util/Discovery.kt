package com.mirrorcast.util

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException

/** Adapted from the UDP device discovery in MirrorCast H264. */
object Discovery {
    private const val PORT = 49742
    private val query = "lan-mirror/1?".toByteArray()
    private const val RESPONSE = "lan-mirror/1:"
    data class Device(val name: String, val address: InetAddress)

    fun advertise(name: String): DatagramSocket {
        val socket = DatagramSocket(PORT)
        val answer = (RESPONSE + name.take(70)).toByteArray()
        Thread({
            val data = ByteArray(256)
            try {
                while (!socket.isClosed) {
                    val packet = DatagramPacket(data, data.size)
                    socket.receive(packet)
                    if (packet.length == query.size && query.indices.all { data[it] == query[it] }) {
                        socket.send(DatagramPacket(answer, answer.size, packet.address, packet.port))
                    }
                }
            } catch (error: SocketException) {
                if (!socket.isClosed) DiagLog.log("接收端发现服务中断：${error.message}")
            } finally {
                socket.close()
            }
        }, "mirror-advertise").start()
        return socket
    }

    fun search(onDevices: (List<Device>) -> Unit): DatagramSocket {
        val socket = DatagramSocket().apply { broadcast = true; soTimeout = 500 }
        Thread({
            val data = ByteArray(512)
            val found = linkedMapOf<InetAddress, Pair<Device, Long>>()
            try {
                while (!socket.isClosed) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    val targets = mutableSetOf(InetAddress.getByName("255.255.255.255"))
                    NetworkInterface.getNetworkInterfaces()?.toList()
                        ?.filter { it.isUp && !it.isLoopback }
                        ?.flatMap { it.interfaceAddresses }
                        ?.mapNotNullTo(targets) { it.broadcast }
                    for (target in targets) {
                        try {
                            socket.send(DatagramPacket(query, query.size, target, PORT))
                        } catch (_: java.io.IOException) {
                            if (socket.isClosed) break
                        }
                    }
                    while (!socket.isClosed && android.os.SystemClock.elapsedRealtime() - now < 1600) {
                        val packet = DatagramPacket(data, data.size)
                        try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                        val address = packet.address
                        val reply = data.decodeToString(0, packet.length)
                        if (address is Inet4Address && !address.isLoopbackAddress &&
                            packet.port == PORT && reply.startsWith(RESPONSE)) {
                            val name = reply.removePrefix(RESPONSE).filterNot { it.isISOControl() }.trim()
                            if (name.isNotEmpty()) found[address] = Device(name, address) to android.os.SystemClock.elapsedRealtime()
                        }
                    }
                    val cutoff = android.os.SystemClock.elapsedRealtime() - 5000
                    found.entries.removeAll { it.value.second < cutoff }
                    if (!socket.isClosed) onDevices(found.values.map { it.first })
                }
            } catch (error: java.io.IOException) {
                if (!socket.isClosed) DiagLog.log("搜索投影仪失败：${error.message}")
            } finally {
                socket.close()
            }
        }, "mirror-search").start()
        return socket
    }
}
