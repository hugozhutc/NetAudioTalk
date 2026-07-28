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

    // 套接字引用
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
        
        // 关闭并释放所有 Socket 资源
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

    // 初始化组播通信
    private fun initUdpMulticast() {
        ioScope.launch {
            try {
                val groupAddr = InetAddress.getByName(targetIp)
                if (currentMode == WorkMode.LISTEN_ONLY) {
                    // 【观察者模式】：必须绑定端口并执行 joinGroup 加入组播，用于纯接收
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
                    // 【飞行员模式】：使用标准 DatagramSocket 发送，严禁加入该组播组，规避物理硬件环回音
                    udpSocket = DatagramSocket().apply {
                        reuseAddress = true
                    }
                    onLog("飞行员组播发送通道就绪。同时启动本地监听接收远端语音...")
                    
                    // 接收同组内其他飞机的语音
                    multicastSocket = MulticastSocket(targetPort).apply {
                        networkInterface = NetworkInterface.getByInetAddress(InetAddress.getByName(localIp))
                        joinGroup(groupAddr)
                    }
                    val buffer = ByteArray(2048)
                    while (isActive && isConnected) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        multicastSocket?.receive(packet)
                        // 单网卡自组网隔离机制：V1.0通过防自环原则，发射端发包时不加入组，但如果Mesh物理层回弹，可通过此IP过滤逻辑进行二次保险
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

    // 初始化单播通信
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

    // 初始化TCP通信（支持点对点链路）
    private fun initTcp() {
        ioScope.launch {
            try {
                if (currentMode == WorkMode.LISTEN_ONLY) {
                    // 观察者作为服务端接收
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
                    // 飞行员作为客户端连接远端
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

    // 发送原始PCM数据接口
    fun sendAudioData(data: ByteArray) {
        // 【硬性强制约束第一条】：如果是观察者纯收听模式，直接无条件强行拦截，杜绝产生任何网络数据包
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
