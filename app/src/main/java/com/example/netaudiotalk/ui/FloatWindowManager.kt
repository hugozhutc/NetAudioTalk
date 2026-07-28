package com.example.netaudiotalk.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.*
import android.widget.Button
import com.example.netaudiotalk.R
import com.example.netaudiotalk.enums.TalkMode
import com.example.netaudiotalk.service.TalkService

class FloatWindowManager(private val context: Context, private val talkService: TalkService) {

    private var windowManager: WindowManager? = null
    private var floatView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    @SuppressLint("ClickableViewAccessibility")
    fun showFloatWindow(talkMode: TalkMode) {
        if (floatView != null) return

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        // 动态创建悬浮窗布局按键
        val btn = Button(context).apply {
            text = if (talkMode == TalkMode.PTT) "按住对讲(悬浮)" else "持续对讲中"
            textSize = 14f
            setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
            setTextColor(android.graphics.Color.WHITE)
            setPadding(20, 10, 20, 10)
        }
        floatView = btn

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = 20
            y = 0
        }

        // 根据对讲模式注入对应的控制逻辑
        if (talkMode == TalkMode.PTT) {
            btn.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        btn.setBackgroundColor(android.graphics.Color.RED)
                        btn.text = "正在发射..."
                        talkService.startPttTransmitting()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        btn.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
                        btn.text = "按住对讲(悬浮)"
                        talkService.stopPttTransmitting()
                        true
                    }
                    else -> false
                }
            }
        } else {
            btn.setBackgroundColor(android.graphics.Color.RED)
            btn.setOnTouchListener(null)
        }

        windowManager?.addView(floatView, layoutParams)
    }

    fun removeFloatWindow() {
        if (floatView != null) {
            windowManager?.removeView(floatView)
            floatView = null
            windowManager = null
        }
    }
}
