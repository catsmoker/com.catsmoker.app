package com.catsmoker.app.util

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

class BoostController(private val context: Context) {
    private val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var dynamicsProcessing: DynamicsProcessing? = null
    private var visualizer: Visualizer? = null
    
    private var hasRestarted = false
    private val handler = Handler(Looper.getMainLooper())
    private var currentBoostLevel: Int = 0

    init {
        setupEffects()
    }

    private fun setupEffects() {
        try {
            loudnessEnhancer?.release()
            // Session 0 (Global)
            loudnessEnhancer = LoudnessEnhancer(0)
        } catch (_: Exception) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                dynamicsProcessing?.release()
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2,
                    false, 0,
                    false, 0,
                    false, 0,
                    true
                ).build()
                dynamicsProcessing = DynamicsProcessing(0, 0, config)
            } catch (_: Exception) {}
        }

        try {
            visualizer?.release()
            // Visualizer(0) trick to keep global session active
            visualizer = Visualizer(0)
            visualizer?.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, s: Int) {}
                override fun onFftDataCapture(v: Visualizer?, f: ByteArray?, s: Int) {}
            }, Visualizer.getMaxCaptureRate() / 2, true, false)
            visualizer?.enabled = false
        } catch (_: Exception) {}
    }

    fun applyBoost(level: Int) {
        currentBoostLevel = level
        try {
            val gainMB = level * 30 // millibels (mB)

            if (loudnessEnhancer == null && (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || dynamicsProcessing == null)) {
                setupEffects()
            }

            loudnessEnhancer?.apply {
                enabled = false
                setTargetGain(gainMB)
                enabled = level > 0
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    dynamicsProcessing?.apply {
                        val gainDB = level / 5f // dB boost
                        val limiter = getLimiterByChannelIndex(0)
                        limiter.isEnabled = level > 0
                        limiter.postGain = gainDB
                        limiter.attackTime = 1f
                        limiter.releaseTime = 60f
                        limiter.ratio = 10f
                        limiter.threshold = -1f
                        
                        setLimiterAllChannelsTo(limiter)
                        enabled = level > 0
                    }
                } catch (_: Exception) {}
            }

            try {
                visualizer?.enabled = false
                if (level > 0) {
                    visualizer?.enabled = true
                }
            } catch (_: Exception) {}

            if (level > 0 && !hasRestarted) {
                restartAudioPlayback()
                hasRestarted = true
            }
        } catch (_: Exception) {}
    }

    fun restartAudioPlayback() {
        val pauseIntent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)
        audioManager.dispatchMediaKeyEvent(pauseIntent)
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        
        handler.postDelayed({
            val playIntent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)
            audioManager.dispatchMediaKeyEvent(playIntent)
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
        }, 100)
    }

    fun release() {
        loudnessEnhancer?.release()
        dynamicsProcessing?.release()
        visualizer?.release()
    }
}
