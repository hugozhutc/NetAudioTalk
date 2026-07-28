package com.example.netaudiotalk.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.*
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.netaudiotalk.enums.CommProtocol
import com.example.netaudiotalk.enums.TalkMode
import com.example.netaudiotalk.enums.WorkMode
import java.net.*

class TalkService : Service() {

    private val binder = TalkBinder()
    private var logListener: ((String) -> Unit)? = null

    // 核心运行状态变量
    private var isSystemRunning = false
    private var isPttTransmitting = false

    // 网络组件
    private var multicastSocket: MulticastSocket? = null
    private var rxThread: Thread? = null
    private var txThread: Thread? = null
    
    // Android 硬件锁：放行 Wi-Fi 组播数据包
    private var multicastLock: WifiManager.MulticastLock? = null

    // 参数缓存区
    private var currentLocalIp: String = ""
    private var currentTargetIp: String = ""
    private var currentPort: Int = 0

    // 音频核心参数 (16kHz, 单声道, 16位PCM)
    private val SAMPLE_RATE = 16000
    private val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
    private val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BUFFER_SIZE = 2048 

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    inner class TalkBinder : Binder() {
        fun getService(): TalkService = this@TalkService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun setLogOutputListener(listener: (String) -> Unit) {
        this.logListener = listener
    }

    private fun postLog(msg: String) {
        Log.d("TalkService", msg)
        logListener?.invoke(msg)
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundProvider()
    }

    // 启动系统核心链路
    fun connectSystem(
        workMode: WorkMode,
        protocol: CommProtocol,
        localIp: String,
        targetIp: String,
        port: Int,
        talkMode: TalkMode
    ) {
        if (isSystemRunning) return
        isSystemRunning = true

        // 强力缓存当前连接参数
        this.currentLocalIp = localIp.trim()
        this.currentTargetIp = targetIp.trim()
        this.currentPort = port

        // 1. 激活 Android 组播锁
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("netaudiotalk_mcast_lock").apply {
                setReferenceCounted(false)
                acquire()
            }
            postLog("已开启 Android 底层网卡硬件组播锁。")
        } catch (e: Exception) {
            postLog("组播锁申请异常: ${e.message}")
        }

        // 2. 初始化音频输出硬件 (播放)
        try {
            val minTrackBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, AUDIO_FORMAT)
            audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                CHANNEL_OUT,
                AUDIO_FORMAT,
                maxOf(minTrackBuf, BUFFER_SIZE * 4),
                AudioTrack.MODE_STREAM
            ).apply { play() }
            postLog("本地音频播放声卡就绪。")
        } catch (e: Exception) {
            postLog("声卡播放初始化失败: ${e.message}")
        }

        // 3. 建立并配置支持网卡绑定的网络接收套接字
        try {
            val localAddr = InetAddress.getByName(currentLocalIp)
            val groupAddr = InetAddress.getByName(currentTargetIp)

            multicastSocket = MulticastSocket(port).apply {
                // 绑定到特定的网络接口，防止多网卡时流量走错物理链路
                val networkInterface = NetworkInterface.getByInetAddress(localAddr)
                if (networkInterface != null) {
                    setNetworkInterface(networkInterface)
                }
                
                // 【保险一】：关闭底层 Linux 组播回环，禁止自己发出的包回弹到自己的接收队列
                loopbackMode = false 

                if (groupAddr.isMCLinkLocal || groupAddr.isMCNodeLocal || groupAddr.isMCOrgLocal || groupAddr.isMCSiteLocal || groupAddr.isMCGlobal) {
                    joinGroup(groupAddr)
                    postLog("网络套接字成功加入组播组: $currentTargetIp:$port (已禁用底层回环)")
                } else {
                    postLog("当前为单播/广播监听模式: $currentTargetIp:$port")
                }
            }
        } catch (e: Exception) {
            postLog("网络套接字建立失败: ${e.message}")
        }

        // 4. 开启异步接收与播放线程
        startReceiverThread()

        // 5. 调度话务模式
        if (workMode == WorkMode.TRANSMIT && talkMode == TalkMode.CONTINUOUS) {
            isPttTransmitting = true
            startTransmitterThread(currentLocalIp, currentTargetIp, currentPort)
        } else if (workMode == WorkMode.TRANSMIT && talkMode == TalkMode.PTT) {
            postLog("核心链路联通。当前为 [按住对讲 (PTT)] 模式，等待按键调度...")
        }
    }

    private fun startReceiverThread() {
        rxThread = Thread {
            val buffer = ByteArray(BUFFER_SIZE)
            while (isSystemRunning) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    multicastSocket?.receive(packet)

                    // 【保险二】：过滤来自本机的幻影包（防止部分定制硬件或热点强行空中转发自己发过的包）
                    val senderIp = packet.address.hostAddress
                    if (senderIp == currentLocalIp) {
                        continue // 拒绝播放自己的声音，直接丢弃
                    }

                    // 写入声卡播放别人的声音
                    audioTrack?.write(packet.data, packet.offset, packet.length)
                } catch (e: Exception) {
                    if (!isSystemRunning) break
                    postLog("数据接收或播放中断: ${e.message}")
                }
            }
            postLog("--> 接收线程已安全关闭。")
        }.apply { start() }
    }

    // PTT 模式按下：触发动态采集与发射
    fun startPttTransmitting() {
        if (!isSystemRunning || isPttTransmitting) return
        isPttTransmitting = true
        
        postLog("PTT 动作响应：开始加载麦克风并打通网络发射链路...")
        startTransmitterThread(currentLocalIp, currentTargetIp, currentPort)
    }

    // PTT 模式松开：瞬间释放麦克风并销毁线程
    fun stopPttTransmitting() {
        if (!isPttTransmitting) return
        isPttTransmitting = false
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {}
        postLog("PTT 动作释放：已终止语音数据发射。")
    }

    private fun startTransmitterThread(localIp: String, targetIp: String, port: Int) {
        txThread = Thread {
            try {
                val minRecBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT)
                // 采用 VOICE_COMMUNICATION 以获得更优的硬件AEC(回音消除)和NS(降噪)
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    CHANNEL_IN,
                    AUDIO_FORMAT,
                    maxOf(minRecBuf, BUFFER_SIZE * 4)
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    postLog("错误：麦克风初始化失败，请确认硬件被抢占或录音权限被拒绝！")
                    isPttTransmitting = false
                    return@Thread
                }

                audioRecord?.startRecording()
                val audioBuffer = ByteArray(BUFFER_SIZE)
                val targetAddress = InetAddress.getByName(targetIp)

                postLog("==> 音频发射线程进入就绪状态，目标地址: $targetIp:$port")
                while (isSystemRunning && isPttTransmitting) {
                    val readBytes = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                    if (readBytes > 0) {
                        val packet = DatagramPacket(audioBuffer, readBytes, targetAddress, port)
                        multicastSocket?.send(packet)
                    }
                }
            } catch (e: Exception) {
                postLog("发射线程突发崩溃: ${e.message}")
            } finally {
                postLog("==> 音频发射线程已安全停机归产。")
            }
        }.apply { start() }
    }

    fun disconnectSystem() {
        isSystemRunning = false
        isPttTransmitting = false

        // 关闭网络套接字
        try { multicastSocket?.close() } catch (e: Exception) {}
        multicastSocket = null

        // 彻底销毁录音
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {}
        audioRecord = null

        // 彻底销毁播放
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {}
        audioTrack = null

        // 回归并释放 Android 硬件锁状态
        if (multicastLock?.isHeld == true) {
            multicastLock?.release()
        }
        multicastLock = null

        postLog("系统服务链路已与专网断开，硬件状态重置完成。")
    }

    private fun startForegroundProvider() {
        val channelId = "netaudiotalk_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "专网前台音频通信保活", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("NetAudioTalk 正在运行")
            .setContentText("专网对讲音频总线持续保活中...")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .build()

        startForeground(1010, notification)
    }

    override fun onDestroy() {
        disconnectSystem()
        super.onDestroy()
    }
}
