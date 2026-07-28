package com.example.netaudiotalk.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.netaudiotalk.databinding.ActivityMainBinding
import com.example.netaudiotalk.enums.CommProtocol
import com.example.netaudiotalk.enums.TalkMode
import com.example.netaudiotalk.enums.WorkMode
import com.example.netaudiotalk.service.TalkService
import com.example.netaudiotalk.utils.NetworkInterfaceHelper
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var talkService: TalkService? = null
    private var isBound = false
    private var isSystemRunning = false
    private lateinit var prefs: SharedPreferences

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TalkService.TalkBinder
            talkService = binder.getService()
            isBound = true
            
            talkService?.setLogOutputListener { msg ->
                runOnUiThread { appendLog(msg) }
            }
            appendLog("后台前台服务联通成功。")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            talkService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        super.onCreate(savedInstanceState)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        prefs = getSharedPreferences("NetAudioTalk_Prefs", Context.MODE_PRIVATE)

        initNetworkCards()
        initProtocolSpinner()
        loadPersistedParams()
        setupUIListeners()
        checkAndRequestPermissions()

        val intent = Intent(this, TalkService::class.java)
        startService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun initNetworkCards() {
        val cardList = NetworkInterfaceHelper.getAvailableNetworkCards()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, cardList)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerNetCard.adapter = adapter
    }

    private fun initProtocolSpinner() {
        val protocols = CommProtocol.values().map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, protocols)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerProtocol.adapter = adapter
    }

    private fun setupUIListeners() {
        binding.rgWorkMode.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == binding.rbObserver.id) {
                binding.rgTalkMode.clearCheck()
                binding.rbPtt.isEnabled = false
                binding.rbContinuous.isEnabled = false
                binding.btnTalkLarge.isEnabled = false
                binding.btnTalkLarge.text = "已锁定 (观察者纯收听模式)"
            } else {
                binding.rbPtt.isEnabled = true
                binding.rbContinuous.isEnabled = true
                binding.rbPtt.isChecked = true
                binding.btnTalkLarge.isEnabled = true
                binding.btnTalkLarge.text = "按住 对讲 (PTT)"
            }
        }

        binding.rgTalkMode.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == binding.rbContinuous.id) {
                binding.btnTalkLarge.text = "持续常开语音发射中"
                binding.btnTalkLarge.isEnabled = false
            } else if (checkedId == binding.rbPtt.id) {
                binding.btnTalkLarge.text = "按住 对讲 (PTT)"
                binding.btnTalkLarge.isEnabled = true
            }
        }

        binding.btnConnect.setOnClickListener {
            if (!isSystemRunning) startSystemAction() else stopSystemAction()
        }

        binding.btnTalkLarge.setOnTouchListener { _, event ->
            if (!isSystemRunning || binding.rbObserver.isChecked || binding.rbContinuous.isChecked) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    binding.btnTalkLarge.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                    binding.btnTalkLarge.text = ">>>> 正在向专网广播语音 <<<<"
                    talkService?.startPttTransmitting()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    binding.btnTalkLarge.setBackgroundColor(ContextCompat.getColor(this, android.R.color.darker_gray))
                    binding.btnTalkLarge.text = "按住 对讲 (PTT)"
                    talkService?.stopPttTransmitting()
                    true
                }
                else -> false
            }
        }

        binding.btnSaveParams.setOnClickListener { saveParams() }
        binding.btnLoadParams.setOnClickListener { loadPersistedParams(); Toast.makeText(this, "参数已重新载入", Toast.LENGTH_SHORT).show() }
    }

    private fun startSystemAction() {
        val selectedCard = binding.spinnerNetCard.selectedItem as? NetworkInterfaceHelper.NetworkCard ?: return
        val proto = CommProtocol.valueOf(binding.spinnerProtocol.selectedItem.toString())
        val targetIp = binding.etTargetIp.text.toString().trim()
        val port = binding.etPort.text.toString().trim().toIntOrNull() ?: 6000
        val workMode = if (binding.rbPilot.isChecked) WorkMode.TRANSMIT else WorkMode.LISTEN_ONLY
        val talkMode = if (binding.rbContinuous.isChecked) TalkMode.CONTINUOUS else TalkMode.PTT

        talkService?.connectSystem(workMode, proto, selectedCard.ip, targetIp, port, talkMode)
        
        isSystemRunning = true
        binding.btnConnect.text = "断开系统"
        toggleUIConfigElements(false)
        appendLog("系统启动成功，核心链路已进入工作状态。")
    }

    private fun stopSystemAction() {
        talkService?.disconnectSystem()
        isSystemRunning = false
        binding.btnConnect.text = "连接系统"
        toggleUIConfigElements(true)
        if (binding.rbPilot.isChecked) {
            binding.btnTalkLarge.isEnabled = true
            binding.btnTalkLarge.text = "按住 对讲 (PTT)"
        }
        appendLog("系统已主动安全断开。")
    }

    private fun toggleUIConfigElements(enabled: Boolean) {
        binding.spinnerNetCard.isEnabled = enabled
        binding.spinnerProtocol.isEnabled = enabled
        binding.etTargetIp.isEnabled = enabled
        binding.etPort.isEnabled = enabled
        binding.rbPilot.isEnabled = enabled
        binding.rbObserver.isEnabled = enabled
        binding.rbPtt.isEnabled = if (binding.rbObserver.isChecked) false else enabled
        binding.rbContinuous.isEnabled = if (binding.rbObserver.isChecked) false else enabled
    }

    private fun saveParams() {
        prefs.edit().apply {
            putString("target_ip", binding.etTargetIp.text.toString())
            putString("target_port", binding.etPort.text.toString())
            putInt("protocol_sel", binding.spinnerProtocol.selectedItemPosition)
            putBoolean("is_pilot", binding.rbPilot.isChecked)
            apply()
        }
        Toast.makeText(this, "当前参数本地持久化保存成功", Toast.LENGTH_SHORT).show()
    }

    private fun loadPersistedParams() {
        binding.etTargetIp.setText(prefs.getString("target_ip", "239.0.0.1"))
        binding.etPort.setText(prefs.getString("target_port", "6000"))
        binding.spinnerProtocol.setSelection(prefs.getInt("protocol_sel", 2))
        if (prefs.getBoolean("is_pilot", true)) {
            binding.rbPilot.isChecked = true
        } else {
            binding.rbObserver.isChecked = true
        }
    }

    private fun appendLog(msg: String) {
        val time = timeFormat.format(Date())
        binding.etLogWindow.append("[$time] $msg\n")
        binding.scrollLog.post { binding.scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        val needed = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 200)
        }

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
        }
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        super.onDestroy()
    }
}
