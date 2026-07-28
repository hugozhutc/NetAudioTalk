package com.example.netaudiotalk.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.*
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.netaudiotalk.enums.CommProtocol
import com.example.netaudiotalk.enums.TalkMode
import com.example.netaudiotalk.enums.WorkMode
import java.net.*
import java.util.concurrent.atomic.AtomicLong

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
    private var currentTalkMode: TalkMode = TalkMode.PTT

    // 音频核心参数 (16kHz, 单声道, 16位PCM)
    private val SAMPLE_RATE = 16000
    private val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
    private val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BUFFER_SIZE = 2048 

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    // ================= 悬浮窗监控与PTT交互组件 =================
    private var windowManager: WindowManager? = null
    private var floatingView: LinearLayout? = null
    private var tvStatus: TextView? = null
    private var tvTraffic: TextView? = null
    private var btnPttTouch: Button? = null // 悬浮窗内的交互式 PTT 按钮
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rxPackets = AtomicLong(0)
    private val txPackets = AtomicLong(0)

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
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
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

        this.currentLocalIp = localIp.trim()
        this.currentTargetIp = targetIp.trim()
        this.currentPort = port
        this.currentTalkMode = talkMode
        
        rxPackets.set(0)
        txPackets.set(0)

        // 1. 初始化并挂载具备交互能力的 PTT 悬浮窗
        mainHandler.post { initAndShowFloatingWindow() }

        // 2. 激活 Android 组播锁
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

        // 3. 初始化音频输出硬件 (播放)
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

        // 4. 建立网络接收套接字
        try {
            val localAddr = InetAddress.getByName(currentLocalIp)
            val groupAddr = InetAddress.getByName(currentTargetIp)

            multicastSocket = MulticastSocket(port).apply {
                val networkInterface = NetworkInterface.getByInetAddress(localAddr)
                if (networkInterface != null) {
                    setNetworkInterface(networkInterface)
                }
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

        // 5. 开启异步接收与播放线程
        startReceiverThread()

        // 6. 调度话务模式
        if (workMode == WorkMode.TRANSMIT && talkMode == TalkMode.CONTINUOUS) {
            isPttTransmitting = true
            startTransmitterThread(currentLocalIp, currentTargetIp, currentPort)
        } else if (workMode == WorkMode.TRANSMIT && talkMode == TalkMode.PTT) {
            postLog("核心链路联通。当前为 [按住对讲 (PTT)] 模式，等待悬浮窗或物理键调度...")
        }
        
        mainHandler.post { refreshFloatingUi() }
    }

    private fun startReceiverThread() {
        rxThread = Thread {
            val buffer = ByteArray(BUFFER_SIZE)
            while (isSystemRunning) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    multicastSocket?.receive(packet)

                    val senderIp = packet.address.hostAddress
                    if (senderIp == currentLocalIp) {
                        continue 
                    }

                    rxPackets.incrementAndGet()
                    if (rxPackets.get() % 10 == 0L) {
                        mainHandler.post { refreshFloatingUi() }
                    }

                    audioTrack?.write(packet.data, packet.offset, packet.length)
                } catch (e: Exception) {
                    if (!isSystemRunning) break
                    postLog("数据接收或播放中断: ${e.message}")
                }
            }
        }.apply { start() }
    }

    // PTT 状态切换控制
    fun startPttTransmitting() {
        if (!isSystemRunning || isPttTransmitting) return
        isPttTransmitting = true
        postLog("PTT 激活：麦克风就绪并联通专网发射端。")
        mainHandler.post { refreshFloatingUi() }
        startTransmitterThread(currentLocalIp, currentTargetIp, currentPort)
    }

    fun stopPttTransmitting() {
        if (!isPttTransmitting) return
        isPttTransmitting = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {}
        postLog("PTT 释放：已关闭语音网络发射。")
        mainHandler.post { refreshFloatingUi() }
    }

    private fun startTransmitterThread(localIp: String, targetIp: String, port: Int) {
        txThread = Thread {
            try {
                val minRecBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT)
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    CHANNEL_IN,
                    AUDIO_FORMAT,
                    maxOf(minRecBuf, BUFFER_SIZE * 4)
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    isPttTransmitting = false
                    return@Thread
                }

                audioRecord?.startRecording()
                val audioBuffer = ByteArray(BUFFER_SIZE)
                val targetAddress = InetAddress.getByName(targetIp)

                while (isSystemRunning && isPttTransmitting) {
                    val readBytes = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                    if (readBytes > 0) {
                        val packet = DatagramPacket(audioBuffer, readBytes, targetAddress, port)
                        multicastSocket?.send(packet)
                        
                        txPackets.incrementAndGet()
                        if (txPackets.get() % 10 == 0L) {
                            mainHandler.post { refreshFloatingUi() }
                        }
                    }
                }
            } catch (e: Exception) {
                postLog("发射线程崩溃: ${e.message}")
            }
        }.apply { start() }
    }

    // ================= 创建带触摸响应的系统全局 PTT 悬浮窗 =================
    @SuppressLint("ClickableViewAccessibility")
    private fun initAndShowFloatingWindow() {
        if (floatingView != null) return

        val context = applicationContext
        
        // 1. 外层面板容器
        floatingView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#DD1A1A1A")) // 稍深的半透明板
            setPadding(30, 20, 30, 20)
        }

        // 2. 状态与流量文本
        tvStatus = TextView(context).apply {
            setTextColor(Color.GREEN)
            textSize = 13f
            text = "专网总线: 已联通"
            gravity = Gravity.CENTER
        }
        
        tvTraffic = TextView(context).apply {
            setTextColor(Color.LTGRAY)
            textSize = 11f
            text = "Rx: 0 包 | Tx: 0 包"
            setPadding(0, 5, 0, 15)
            gravity = Gravity.CENTER
        }

        // 3. 【核心交互改动】：动态画一个全局 PTT 实体按钮
        btnPttTouch = Button(context).apply {
            text = "按住 对讲"
            setBackgroundColor(Color.parseColor("#FF0055CC")) // 默认科技蓝
            setTextColor(Color.WHITE)
            textSize = 14f
            
            // 绑定触摸事件，完美复刻主界面的 PTT 操作逻辑
            setOnTouchListener { _, event ->
                if (!isSystemRunning || currentTalkMode != TalkMode.PTT) return@setOnTouchListener false
                
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        // 按下：按钮变红，触发录音与网络发射
                        setBackgroundColor(Color.parseColor("#DDBB0000"))
                        text = "正在 说话..."
                        startPttTransmitting()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        // 抬起或滑出：恢复蓝色，关闭录音与网络发射
                        setBackgroundColor(Color.parseColor("#FF0055CC"))
                        text = "按住 对讲"
                        stopPttTransmitting()
                    }
                }
                true
            }
        }

        floatingView?.addView(tvStatus)
        floatingView?.addView(tvTraffic)
        floatingView?.addView(btnPttTouch)

        // 4. 修改 LayoutParams 标志：取消 FLAG_NOT_TOUCHABLE 锁，使悬浮窗内的按钮能感知手指按下抬起
        val layoutParams = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            
            // 【关键】：这里移除了阻断点击的 flag，保留 NOT_FOCUSABLE 确保不阻挡输入法，但允许处理触摸
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    
            format = PixelFormat.TRANSLUCENT
            width = 320 // 给定一个固定宽度，使按钮在桌面上更加美观紧凑
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.END // 默认钉在手持机屏幕右上角
            x = 50
            y = 120 // 往下挪一点，防止挡住系统状态栏的时间与电量
        }

        try {
            windowManager?.addView(floatingView, layoutParams)
        } catch (e: Exception) {
            Log.e("TalkService", "挂载交互式对讲悬浮窗失败: ${e.message}")
        }
    }

    private fun refreshFloatingUi() {
        if (!isSystemRunning) {
            tvStatus?.text = "专网总线: 已断开"
            tvStatus?.setTextColor(Color.RED)
            btnPttTouch?.visibility = View.GONE
            return
        }

        btnPttTouch?.visibility = if (currentTalkMode == TalkMode.PTT) View.VISIBLE else View.GONE

        if (isPttTransmitting) {
            tvStatus?.text = "话务状态: 正在发射声音..."
            tvStatus?.setTextColor(Color.RED)
        } else {
            tvStatus?.text = "话务状态: 监听中 (${if(currentTalkMode == TalkMode.PTT) "PTT" else "常开"})"
            tvStatus?.setTextColor(Color.GREEN)
        }

        tvTraffic?.text = "接收: ${rxPackets.get()} 包 | 发送: ${txPackets.get()} 包"
    }

    private fun removeFloatingWindow() {
        floatingView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {}
            floatingView = null
            tvStatus = null
            tvTraffic = null
            btnPttTouch = null
        }
    }

    fun disconnectSystem() {
        isSystemRunning = false
        isPttTransmitting = false

        mainHandler.post { 
            refreshFloatingUi()
            removeFloatingWindow()
        }

        try { multicastSocket?.close() } catch (e: Exception) {}
        multicastSocket = null

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

        if (multicastLock?.isHeld == true) {
            multicastLock?.release()
        }
        multicastLock = null

        postLog("系统服务链路已断开，交互悬浮窗安全撤销。")
    }

    private fun startForegroundProvider() {
        val channelId = "netaudiotalk_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "专网前台音频通信保活", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("NetAudioTalk 后台对讲")
            .setContentText("全局对讲总线与桌面浮窗处于极高保活状态...")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1010, 
                notification, 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(1010, notification)
        }
    }

    override fun onDestroy() {
        disconnectSystem()
        super.onDestroy()
    }
}
