package com.catsmoker.app.features.editgamefiles.wuwa

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import com.catsmoker.app.shared.util.DisplayMetricsProvider

/**
 * The real device facts the WuWa generator and the SmartBrain recommendation run on.
 *
 * The reference app collected these by asking the user to upload the game's own log — the
 * GL_RENDERER string, the model, the resolution. Every one of them is also directly
 * answerable on-device, so this reads them here instead: the GL renderer from a throwaway
 * 1×1 EGL context (the same string the log carried), the model and SoC from `Build`, the
 * panel resolution from [DisplayMetricsProvider] — the single reader the display facts are
 * allowed to come from.
 *
 * Best-effort by contract: every field is nullable and every source is failure-guarded.
 * A null means "this device would not say", and the consumers fall back to their unread
 * behaviour ([WuWaConfigGenerator]'s universal lowest tier, the recommendation's unscored
 * axes) rather than inventing a value.
 *
 * The EGL probe must be called from a background thread (it creates and destroys a pbuffer
 * surface); [WuwaConfigViewModel] reads it once per process on its IO dispatcher.
 */
object WuwaDeviceInfo {

    /**
     * Reads everything the generator and the recommender consume from this device.
     * Never throws: each source falls back to null on its own.
     */
    fun read(metrics: DisplayMetricsProvider): WuWaConfigGenerator.DeviceInfo {
        val snapshot = metrics.current()
        return WuWaConfigGenerator.DeviceInfo(
            gpu = probeGlesRenderer(),
            deviceModel = runCatching { Build.MODEL?.takeIf { it.isNotBlank() } }.getOrNull(),
            socModel = runCatching { readSocModel() }.getOrNull(),
            resolution = snapshot.sizeLabel.takeIf { snapshot.isValid }
        )
    }

    /**
     * `SOC_MODEL` where the platform reports one (API 31+ — e.g. `SM8550`), else the
     * `HARDWARE` board name (`surya`, `kalama`). Both feed the generator's chipset-profile
     * table; the GPU string below covers the cases neither name matches.
     */
    private fun readSocModel(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.takeIf { it.isNotBlank() && it != "unknown" }
                ?: Build.HARDWARE.takeIf { it.isNotBlank() }
        } else {
            Build.HARDWARE.takeIf { it.isNotBlank() }
        }

    /**
     * The GL renderer string — "Adreno (TM) 618", "Mali-G78 MC4" — read from a 1×1 EGL
     * pbuffer this app creates and destroys. It is the same fact the reference read out of
     * the uploaded log's GL_RENDERER line; here the driver answers directly.
     *
     * Any failure (no EGL, driver refusal, a native load error) returns null, and callers
     * treat the GPU as unread.
     */
    fun probeGlesRenderer(): String? = runCatching { probeGlesRendererOrNull() }.getOrNull()

    private fun probeGlesRendererOrNull(): String? {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return null
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null
        try {
            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            val chosen = EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)
            val config = configs[0]
            if (!chosen || numConfigs[0] == 0 || config == null) return null

            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (context == EGL14.EGL_NO_CONTEXT) return null
            try {
                val surfaceAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
                val surface = EGL14.eglCreatePbufferSurface(display, config, surfaceAttribs, 0)
                if (surface == EGL14.EGL_NO_SURFACE) return null
                try {
                    if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null
                    return GLES20.glGetString(GLES20.GL_RENDERER)?.takeIf { it.isNotBlank() }
                } finally {
                    EGL14.eglDestroySurface(display, surface)
                }
            } finally {
                EGL14.eglDestroyContext(display, context)
            }
        } finally {
            EGL14.eglTerminate(display)
        }
    }
}
