package com.example.netaudiotalk.constants

import android.media.AudioFormat

object AudioConfig {
    const val SAMPLE_RATE = 16000
    const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
    const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
    const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    
    // 每20ms传输一帧音频数据：16000 * 1ch * 2bytes * 0.02s = 640 bytes
    const val BUFFER_FRAME_SIZE = 640 
}
