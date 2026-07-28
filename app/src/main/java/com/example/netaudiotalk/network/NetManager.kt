package com.example.netaudiotalk.network

import android.util.Log
import com.example.netaudiotalk.enums.CommProtocol
import com.example.netaudiotalk.enums.WorkMode
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.*

class NetManager(private val onDataReceived: (ByteArray) -> Unit, private val onLog: (String) -> Unit) {

    private var currentMode = WorkMode.LISTEN_ONLY
    private var protocol = CommProtocol.UDP_MULTICAST
    private var localIp: String = "0.0.0.0"
    private var targetIp: String = "239.0.0.1"
    private var targetPort: Int = 6000

    private var isConnected = false
    private var ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var udpSocket: DatagramSocket? = null
    private var multicastSocket: MulticastSocket? = null
    private var tcpServerSocket: ServerSocket? = null
    private var tcpClientSocket: Socket? = null
    private var tcpOutputStream: OutputStream? = null

    fun configure(mode: WorkMode, proto: CommProtocol, local: String, target: String, port: Int) {
        this.currentMode = mode
        this.protocol = proto
        this.localIp = local
        this.targetIp = target
        this.targetPort = port
    }

    @Synchronized
    fun start() {
        if (isConnected) return
        isConnected = true
        ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        onLog("正在启动网络模块: 模式=$currentMode, 协议=$protocol, 目标=$targetIp:$targetPort")
        
        when (protocol) {
            CommProtocol.UDP_MULTICAST -> initUdpMulticast()
            CommProtocol.UDP_UNICAST -> initUdpUnicast()
            CommProtocol.TCP -> initTcp()
        }
    }

    @Synchronized
    fun stop() {
        if (!isConnected) return
        isConnected = false
        ioScope.cancel()
        
        runCatching { multicastSocket?.leaveGroup(InetAddress.getByName(targetIp)); multicastSocket?.close() }
        runCatching { udpSocket?.close() }
        runCatching { tcpOutputStream?.close(); tcpClientSocket?.close(); tcpServerSocket?.close() }
        
        multicastSocket = null
        udpSocket = null
        tcpServerSocket = null
        tcpClientSocket = null
        tcpOutputStream = null
        
        onLog("网络模块已完全断开。")
    }

    private fun initUdpMulticast() {
        ioScope.launch {
            try {
                val groupAddr = InetAddress.getByName(targetIp)
                if (currentMode == WorkMode.LISTEN_ONLY) {
                    multicastSocket = MulticastSocket(targetPort).apply {
                        networkInterface = NetworkInterface.getByInetAddress(InetAddress.getByName(localIp))
                        joinGroup(groupAddr)
                    }
                    onLog("观察者已加入组播组: $targetIp")
                    
                    val buffer = ByteArray(2048)
                    while (isActive && isConnected) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        multicastSocket?.receive(packet)
                        if (packet.length > 0) {
                            onDataReceived(packet.data.copyOfRange(0, packet.length))
                        }
                    }
                } else {
                    udpSocket = DatagramSocket().apply {
                        reuseAddress = true
                    }
                    onLog("飞行员组播发送通道就绪。同时启动本地监听接收远端语音...")
                    
                    multicastSocket = MulticastSocket(targetPort).apply {
                        networkInterface = NetworkInterface.getByInetAddress(InetAddress.getByName(localIp))
                        joinGroup(groupAddr)
                    }
                    val buffer = ByteArray(2048)
                    while (isActive && isConnected) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        multicastSocket?.receive(packet)
                        if (packet.length > 0) {
                            onDataReceived(packet.data.copyOfRange(0, packet.length))
                        }
                    }
                }
            } catch (e: Exception) {
                if (isConnected) onLog("组播网络异常: ${e.message}")
            }
        }
    }

    private fun initUdpUnicast() {
        ioScope.launch {
            try {
                udpSocket = DatagramSocket(targetPort, InetAddress.getByName(localIp))
                onLog("UDP单播端口已绑定: $localIp:$targetPort")
                val buffer = ByteArray(2048)
                while (isActive && isConnected) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket?.receive(packet)
                    if (packet.length > 0) {
                        onDataReceived(packet.data.copyOfRange(0, packet.length))
                    }
                }
            } catch (e: Exception) {
                if (isConnected) onLog("UDP单播异常: ${e.message}")
            }
        }
    }

    private fun initTcp() {
        ioScope.launch {
            try {
                if (currentMode == WorkMode.LISTEN_ONLY) {
                    tcpServerSocket = ServerSocket(targetPort, 50, InetAddress.getByName(localIp))
                    onLog("TCP服务器监听中: $localIp:$targetPort")
                    while (isActive && isConnected) {
                        val socket = tcpServerSocket?.accept()
                        onLog("接收到远端TCP客户端连接: ${socket?.remoteSocketAddress}")
                        ioScope.launch {
                            val ins = socket?.getInputStream()
                            val buffer = ByteArray(2048)
                            while (isActive && isConnected) {
                                val len = ins?.read(buffer) ?: -1
                                if (len == -1) break
                                onDataReceived(buffer.copyOfRange(0, len))
                            }
                        }
                    }
                } else {
                    onLog("正在连接远端TCP服务器: $targetIp:$targetPort")
                    tcpClientSocket = Socket()
                    tcpClientSocket?.connect(InetSocketAddress(targetIp, targetPort), 5000)
                    tcpOutputStream = tcpClientSocket?.getOutputStream()
                    onLog("TCP连接成功。")
                    
                    val ins = tcpClientSocket?.getInputStream()
                    val buffer = ByteArray(2048)
                    while (isActive && isConnected) {
                        val len = ins?.read(buffer) ?: -1
                        if (len == -1) break
                        onDataReceived(buffer.copyOfRange(0, len))
                    }
                }
            } catch (e: Exception) {
                if (isConnected) onLog("TCP链路异常: ${e.message}")
            }
        }
    }

    fun sendAudioData(data: ByteArray) {
        if (currentMode == WorkMode.LISTEN_ONLY) {
            return
        }
        if (!isConnected) return

        ioScope.launch {
            try {
                when (protocol) {
                    CommProtocol.UDP_MULTICAST -> {
                        val packet = DatagramPacket(data, data.size, InetAddress.getByName(targetIp), targetPort)
                        udpSocket?.send(packet)
                    }
                    CommProtocol.UDP_UNICAST -> {
                        val packet = DatagramPacket(data, data.size, InetAddress.getByName(targetIp), targetPort)
                        udpSocket?.send(packet)
                    }
                    CommProtocol.TCP -> {
                        tcpOutputStream?.write(data)
                        tcpOutputStream?.flush()
                    }
                }
            } catch (e: Exception) {
                Log.e("NetManager", "数据发送失败: ${e.message}")
            }
        }
    }
}
