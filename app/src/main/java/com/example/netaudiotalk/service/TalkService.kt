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
import android.graphics.drawable.GradientDrawable
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
    
    // 重新设计的UI组件
    private var viewStatusDot: View? = null     // 雷达呼吸状态指示灯
    private var tvStatusTitle: TextView? = null // 主状态文本
    private var tvTrafficRx: TextView? = null   // 独立高光下行数据流
    private var tvTrafficTx: TextView? = null   // 独立高光上行数据流
    private var btnPttTouch: Button? = null    // 战术级交互式 PTT 按钮
    
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
                    if (rxPackets.get() % 5 == 0L) { // 提高刷新频率，数据流像仪表盘一样跳动
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
                        if (txPackets.get() % 5 == 0L) {
                            mainHandler.post { refreshFloatingUi() }
                        }
                    }
                }
            } catch (e: Exception) {
                postLog("发射线程崩溃: ${e.message}")
            }
        }.apply { start() }
    }

    // ================= 创建“战术硬核科技风”全局 PTT 悬浮窗 =================
    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun initAndShowFloatingWindow() {
        if (floatingView != null) return

        val context = applicationContext
        
        // 1. 外层战术防眩光半透明面板 (深灰碳素 + 24dp 质感大圆角)
        floatingView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0) // 已修复：改用 Android 标准多入参函数
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#EE1A1C1F")) // 深碳素灰背景
                cornerRadius = 24f  // 科技感圆角
                setStroke(2, Color.parseColor("#44A0A5B5")) // 极细半透明钛金边框
            }
            background = bg
        }

        // 2. 状态栏容器 (水平对齐：雷达呼吸灯 + 标题)
        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(25, 20, 25, 12)
        }

        // 2a. 雷达指示灯圆点
        viewStatusDot = View(context).apply {
            val dotParams = LinearLayout.LayoutParams(16, 16).apply {
                rightMargin = 15
            }
            layoutParams = dotParams
            val dotDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF00FF66")) // 默认军工荧光绿
            }
            background = dotDrawable
        }

        // 2b. 核心状态文本
        tvStatusTitle = TextView(context).apply {
            setTextColor(Color.parseColor("#FFEEEEEE"))
            textSize = 12f
            text = "LINK READY"
            setPadding(0, 0, 0, 0)
        }
        headerLayout.addView(viewStatusDot)
        headerLayout.addView(tvStatusTitle)

        // 3. 数据仪表盘区 (并排展示，模拟飞行控制台)
        val dashboardLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(25, 0, 25, 20)
            weightSum = 2.0f
        }

        tvTrafficRx = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            setTextColor(Color.parseColor("#FF00D6FF")) // 青蓝色代表下行接收
            textSize = 10f
            text = "RX: 0 Pkts"
        }

        tvTrafficTx = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            setTextColor(Color.parseColor("#FFFFB900")) // 琥珀橙代表上行发射
            textSize = 10f
            text = "TX: 0 Pkts"
            gravity = Gravity.END
        }
        dashboardLayout.addView(tvTrafficRx)
        dashboardLayout.addView(tvTrafficTx)

        // 4. 【战术对讲 PTT 交互按钮】：采用双层高光切换特效
        val pttDefaultBg = GradientDrawable().apply {
            setColor(Color.parseColor("#FF0D6EFD")) // 电子脉冲蓝
            cornerRadius = 16f
            setStroke(1, Color.parseColor("#88FFFFFF"))
        }
        
        val pttActiveBg = GradientDrawable().apply {
            setColor(Color.parseColor("#DDC53939")) // 战术警示红
            cornerRadius = 16f
            setStroke(3, Color.parseColor("#FFFF0033")) // 强光边缘
        }

        btnPttTouch = Button(context).apply {
            text = "▲ PTT TRANSMIT"
            background = pttDefaultBg
            setTextColor(Color.WHITE)
            textSize = 13f
            includeFontPadding = false
            
            val btnParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                88 // 锁定战术厚度，单手大拇指极易盲操
            ).apply {
                setMargins(20, 0, 20, 20)
            }
            layoutParams = btnParams

            // 深度触控交互映射
            setOnTouchListener { _, event ->
                if (!isSystemRunning || currentTalkMode != TalkMode.PTT) return@setOnTouchListener false
                
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        background = pttActiveBg
                        text = "● BROADCASTING..."
                        startPttTransmitting()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        background = pttDefaultBg
                        text = "▲ PTT TRANSMIT"
                        stopPttTransmitting()
                    }
                }
                true
            }
        }

        // 按序注入全局骨架
        floatingView?.addView(headerLayout)
        floatingView?.addView(dashboardLayout)
        floatingView?.addView(btnPttTouch)

        // 5. 跨应用悬浮图层参数布局
        val layoutParams = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    
            format = PixelFormat.TRANSLUCENT
            width = 360 // 完美适配宽比例手持遥控器边缘
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.END
            x = 40
            y = 150 // 优雅避让原生状态栏
        }

        try {
            windowManager?.addView(floatingView, layoutParams)
        } catch (e: Exception) {
            Log.e("TalkService", "UI重构挂载异常: ${e.message}")
        }
    }

    @SuppressLint("SetTextI18n")
    private fun refreshFloatingUi() {
        if (!isSystemRunning) {
            tvStatusTitle?.text = "BUS OFFLINE"
            tvStatusTitle?.setTextColor(Color.parseColor("#FFAAAAAA"))
            (viewStatusDot?.background as? GradientDrawable)?.setColor(Color.GRAY)
            btnPttTouch?.visibility = View.GONE
            return
        }

        btnPttTouch?.visibility = if (currentTalkMode == TalkMode.PTT) View.VISIBLE else View.GONE

        if (isPttTransmitting) {
            tvStatusTitle?.text = "TX ACTIVE"
            tvStatusTitle?.setTextColor(Color.parseColor("#FFFF3B30"))
            // 发射时指示灯变为深警示红
            (viewStatusDot?.background as? GradientDrawable)?.setColor(Color.parseColor("#FFFF3B30"))
        } else {
            val modeSuffix = if (currentTalkMode == TalkMode.PTT) "PTT" else "OPEN"
            tvStatusTitle?.text = "LISTENING [$modeSuffix]"
            tvStatusTitle?.setTextColor(Color.parseColor("#FF00FF66"))
            // 监听中恢复军工荧光绿
            (viewStatusDot?.background as? GradientDrawable)?.setColor(Color.parseColor("#FF00FF66"))
        }

        tvTrafficRx?.text = "RX: ${rxPackets.get()} Pkts"
        tvTrafficTx?.text = "TX: ${txPackets.get()} Pkts"
    }

    private fun removeFloatingWindow() {
        floatingView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {}
            floatingView = null
            viewStatusDot = null
            tvStatusTitle = null
            tvTrafficRx = null
            tvTrafficTx = null
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
