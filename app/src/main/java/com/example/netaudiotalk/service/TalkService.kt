package com.example.netaudiotalk.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.netaudiotalk.enums.CommProtocol
import com.example.netaudiotalk.enums.TalkMode
import com.example.netaudiotalk.enums.WorkMode
import com.example.netaudiotalk.audio.AudioPlayer
import com.example.netaudiotalk.audio.AudioRecorder
import com.example.netaudiotalk.network.NetManager
import com.example.netaudiotalk.ui.FloatWindowManager

class TalkService : Service() {

    private val binder = TalkBinder()
    
    // 核心组件实例生命周期归后台服务统一掌控维护
    lateinit var netManager: NetManager
    private lateinit var audioPlayer: AudioPlayer
    private var audioRecorder: AudioRecorder? = null
    private var floatWindowManager: FloatWindowManager? = null

    private var logListener: ((String) -> Unit)? = null

    inner class TalkBinder : Binder() {
        fun getService(): TalkService = this@TalkService
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundService()

        audioPlayer = AudioPlayer()
        
        // 实例化网络组件，注入回调
        netManager = NetManager(
            onDataReceived = { pcmData ->
                // 收到网络流实时推送至喇叭解码播放
                audioPlayer.playRawData(pcmData)
            },
            onLog = { logMsg ->
                logListener?.invoke(logMsg)
            }
        )

        audioRecorder = AudioRecorder { pcmFrame ->
            // 麦克风采集完毕实时送入网口发送
            netManager.sendAudioData(pcmFrame)
        }

        floatWindowManager = FloatWindowManager(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    fun setLogOutputListener(listener: (String) -> Unit) {
        this.logListener = listener
    }

    // 核心连接入口控制
    fun connectSystem(mode: WorkMode, protocol: CommProtocol, localIp: String, targetIp: String, port: Int, talkMode: TalkMode) {
        netManager.configure(mode, protocol, localIp, targetIp, port)
        netManager.start()
        audioPlayer.startPlaying()

        if (mode == WorkMode.TRANSMIT) {
            if (talkMode == TalkMode.CONTINUOUS) {
                audioRecorder?.startRecording()
            }
            floatWindowManager?.showFloatWindow(talkMode)
        } else {
            // 观察者模式无条件停止物理录音
            audioRecorder?.stopRecording()
            floatWindowManager?.removeFloatWindow()
        }
    }

    fun disconnectSystem() {
        audioRecorder?.stopRecording()
        audioPlayer.stopPlaying()
        netManager.stop()
        floatWindowManager?.removeFloatWindow()
    }

    // PTT 手动物理触发器
    fun startPttTransmitting() {
        audioRecorder?.startRecording()
    }

    fun stopPttTransmitting() {
        audioRecorder?.stopRecording()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        disconnectSystem()
        floatWindowManager?.removeFloatWindow()
        super.onDestroy()
    }

    // ================== 十、 预留扩展接口定义 ==================
    fun setWorkMode(mode: WorkMode) {
        // V1.0仅留空实现，不开发业务逻辑。专为V1.1迭代串口动态更改设备系统角色预留。
    }

    fun playTtsBroadcast(sceneText: String) {
        // V1.0仅定义空函数接口。专为后续链路状态及模式切换语音播报预留。
    }

    private fun startForegroundService() {
        val channelId = "netaudiotalk_service"
        val channelName = "专网语音对讲业务服务"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW).apply {
                lightColor = Color.BLUE
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(chan)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setOngoing(true)
            .setContentTitle("NetAudioTalk 持续运行中")
            .setContentText("无线Mesh专网对讲音频流监听与收发保持")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
            
        startForeground(101, notification)
    }
}
