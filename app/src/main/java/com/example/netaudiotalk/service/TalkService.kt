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
        netManager = NetManager(
            onDataReceived = { pcmData -> audioPlayer.playRawData(pcmData) },
            onLog = { logMsg -> logListener?.invoke(logMsg) }
        )

        audioRecorder = AudioRecorder { pcmFrame -> netManager.sendAudioData(pcmFrame) }
        floatWindowManager = FloatWindowManager(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    fun setLogOutputListener(listener: (String) -> Unit) {
        this.logListener = listener
    }

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

    // 预留接口扩展实现
    fun setWorkMode(mode: WorkMode) {}
    fun playTtsBroadcast(sceneText: String) {}

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
