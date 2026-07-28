package com.example.netaudiotalk.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.example.netaudiotalk.constants.AudioConfig

class AudioPlayer {

    private var audioTrack: AudioTrack? = null
    private var isPlaying = false

    fun startPlaying() {
        if (isPlaying) return
        isPlaying = true

        val minBufferSize = AudioTrack.getMinBufferSize(
            AudioConfig.SAMPLE_RATE,
            AudioConfig.CHANNEL_CONFIG_OUT,
            AudioConfig.AUDIO_FORMAT
        )

        audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(AudioConfig.SAMPLE_RATE)
                .setChannelMask(AudioConfig.CHANNEL_CONFIG_OUT)
                .setEncoding(AudioConfig.AUDIO_FORMAT)
                .build(),
            Math.max(minBufferSize, AudioConfig.BUFFER_FRAME_SIZE * 4),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )

        try {
            audioTrack?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun playRawData(data: ByteArray) {
        if (!isPlaying || audioTrack == null) return
        try {
            audioTrack?.write(data, 0, data.size)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopPlaying() {
        if (!isPlaying) return
        isPlaying = false
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioTrack = null
    }
}
