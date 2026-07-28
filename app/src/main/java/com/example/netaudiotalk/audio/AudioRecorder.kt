package com.example.netaudiotalk.audio

import android.annotation.SuppressLint
import android.media.AudioRecord
import android.media.MediaRecorder
import com.example.netaudiotalk.constants.AudioConfig
import kotlinx.coroutines.*

class AudioRecorder(private val onAudioFrameCaptured: (ByteArray) -> Unit) {

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (isRecording) return
        isRecording = true

        val minBufferSize = AudioRecord.getMinBufferSize(
            AudioConfig.SAMPLE_RATE,
            AudioConfig.CHANNEL_CONFIG_IN,
            AudioConfig.AUDIO_FORMAT
        )
        val bufferSize = Math.max(minBufferSize, AudioConfig.BUFFER_FRAME_SIZE * 4)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            AudioConfig.SAMPLE_RATE,
            AudioConfig.CHANNEL_CONFIG_IN,
            AudioConfig.AUDIO_FORMAT,
            bufferSize
        )

        audioRecord?.startRecording()
        
        recordJob = scope.launch {
            val audioBuffer = ByteArray(AudioConfig.BUFFER_FRAME_SIZE)
            while (isActive && isRecording) {
                val readBytes = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                if (readBytes > 0) {
                    onAudioFrameCaptured(audioBuffer.copyOfRange(0, readBytes))
                }
            }
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        recordJob?.cancel()
        recordJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioRecord = null
    }
}
