package com.catsmoker.app.features.gamingtools.tools.audio

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import javax.inject.Inject

class BoostController(context: Context) {
    private val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var enhancer: LoudnessEnhancer? = null
    private var dynamicsProcessing: DynamicsProcessing? = null
    private var visualizer: Visualizer? = null
    private val handler = Handler(Looper.getMainLooper())

    private var hasRestarted = false

    fun applyBoost(level: Int) {
        try {
            if (enhancer == null) {
                enhancer = LoudnessEnhancer(0)
            }
            enhancer?.setTargetGain(level * 30)
            enhancer?.enabled = level > 0

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (dynamicsProcessing == null) {
                    val config = DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                        2,
                        false, 0,
                        false, 0,
                        false, 0,
                        true
                    ).build()
                    dynamicsProcessing = DynamicsProcessing(0, 0, config)
                }
                dynamicsProcessing?.apply {
                    val gainDB = level / 5f
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
            }

            // Visualizer trick to keep session alive
            if (visualizer == null) {
                visualizer = Visualizer(0)
                visualizer?.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, s: Int) {}
                    override fun onFftDataCapture(v: Visualizer?, f: ByteArray?, s: Int) {}
                }, Visualizer.getMaxCaptureRate() / 2, true, false)
            }
            visualizer?.enabled = false
            if (level > 0) {
                visualizer?.enabled = true
            }

            if (level > 0 && !hasRestarted) {
                restartAudioPlayback()
                hasRestarted = true
            } else if (level == 0) {
                hasRestarted = false
            }
        } catch (_: Exception) {}
    }

    private fun restartAudioPlayback() {
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
        enhancer?.enabled = false
        enhancer?.release()
        enhancer = null
        dynamicsProcessing?.enabled = false
        dynamicsProcessing?.release()
        dynamicsProcessing = null
        visualizer?.enabled = false
        visualizer?.release()
        visualizer = null
    }
}
