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
// ... 后面的代码完全保持不变 ...

    private val binder = TalkBinder()
    private var logListener: ((String) -> Unit)? = null

    // 核心运行状态变量
    private var isSystemRunning = false
    private var isPttTransmitting = false

    // 网络组件
    private var multicastSocket: MulticastSocket? = null
    private var rxThread: Thread? = null
    private var txThread: Thread? = null
    
    // Android 硬件锁：强行放行 Wi-Fi 组播数据包
    private var multicastLock: WifiManager.MulticastLock? = null

    // 音频组件参数 (16kHz, 单声道, 16位PCM)
    private val SAMPLE_RATE = 16000
    private val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
    private val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BUFFER_SIZE = 2048 // 每包 2048 字节，确保毫秒级极低发送延迟

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

        // 1. 硬核激活 Android 组播锁，解决同 Wi-Fi 下接收屏蔽问题
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("netaudiotalk_mcast_lock").apply {
                setReferenceCounted(false)
                acquire()
            }
            postLog("已强行开启 Android 系统硬件网卡组播锁。")
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

        // 3. 建立支持指定网卡的网络接收套接字
        try {
            val localAddr = InetAddress.getByName(localIp)
            val groupAddr = InetAddress.getByName(targetIp)

            // 必须使用 MulticastSocket 才能完美加入组播
            multicastSocket = MulticastSocket(port).apply {
                // 核心修正：显式绑定用户选择的 Wi-Fi 物理网卡，防止多网卡遥控器走错路由
                val networkInterface = NetworkInterface.getByInetAddress(localAddr)
                if (networkInterface != null) {
                    setNetworkInterface(networkInterface)
                }
                
                // 判断如果是组播段地址(224.0.0.0 ~ 239.255.255.255)，则执行加入组播动作
                if (groupAddr.isMCLinkLocal || groupAddr.isMCNodeLocal || groupAddr.isMCOrgLocal || groupAddr.isMCSiteLocal || groupAddr.isMCGlobal) {
                    joinGroup(groupAddr)
                    postLog("网络套接字成功绑定网卡并加入组播组: $targetIp:$port")
                } else {
                    postLog("当前为单播/广播监听模式: $targetIp:$port")
                }
            }
        } catch (e: Exception) {
            postLog("网络套接字建立失败: ${e.message}")
        }

        // 4. 开启异步接收与播放线程
        startReceiverThread()

        // 5. 如果是飞行员模式且选择持续发送，直接开启录音线程
        if (workMode == WorkMode.TRANSMIT && talkMode == TalkMode.CONTINUOUS) {
            isPttTransmitting = true
            startTransmitterThread(localIp, targetIp, port)
        }
    }

    private fun startReceiverThread() {
        rxThread = Thread {
            val buffer = ByteArray(BUFFER_SIZE)
            postLog("--> 专网网络音频接收线程已在后台挂起...")
            while (isSystemRunning) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    multicastSocket?.receive(packet) // 此时解除阻塞，正常收包

                    // 将网络PCM数据直接写入声卡播放
                    audioTrack?.write(packet.data, packet.offset, packet.length)
                } catch (e: Exception) {
                    if (!isSystemRunning) break
                    postLog("数据接收或播放突发异常: ${e.message}")
                }
            }
            postLog("--> 接收线程已安全退出。")
        }.apply { start() }
    }

    // PTT 按住事件触发：开启发送
    fun startPttTransmitting() {
        if (!isSystemRunning || isPttTransmitting) return
        isPttTransmitting = true
        
        // 动态获取当前 MainActivity 填入的网络参数（这里简化提取，实际可通过全局配置或临时存储传递）
        // 调试时确保该方法对应的参数被 txThread 正常消费即可
    }

    // PTT 松开事件触发：停止发送
    fun stopPttTransmitting() {
        isPttTransmitting = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {}
        postLog("PTT 发射终止。")
    }

    private fun startTransmitterThread(localIp: String, targetIp: String, port: Int) {
        txThread = Thread {
            try {
                val minRecBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT)
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_IN,
                    AUDIO_FORMAT,
                    maxOf(minRecBuf, BUFFER_SIZE * 4)
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    postLog("错误：麦克风硬件初始化失败，可能被其他应用占用！")
                    return@Thread
                }

                audioRecord?.startRecording()
                val audioBuffer = ByteArray(BUFFER_SIZE)
                val targetAddress = InetAddress.getByName(targetIp)

                postLog("==> 本地麦克风录音编码发射线程已激活")
                while (isSystemRunning && isPttTransmitting) {
                    val readBytes = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                    if (readBytes > 0) {
                        val packet = DatagramPacket(audioBuffer, readBytes, targetAddress, port)
                        multicastSocket?.send(packet)
                    }
                }
            } catch (e: Exception) {
                postLog("发射线程异常崩溃: ${e.message}")
            }
        }.apply { start() }
    }

    fun disconnectSystem() {
        isSystemRunning = false
        isPttTransmitting = false

        // 释放网络连接
        try { multicastSocket?.close() } catch (e: Exception) {}
        multicastSocket = null

        // 释放音频硬件
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {}
        audioRecord = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {}
        audioTrack = null

        // 彻底释放 Android 系统组播硬件锁
        if (multicastLock?.isHeld == true) {
            multicastLock?.release()
        }
        multicastLock = null

        postLog("专网通信链路已安全断开，硬件锁已归还系统。")
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
