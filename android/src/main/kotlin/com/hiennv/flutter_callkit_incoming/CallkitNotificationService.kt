package com.hiennv.flutter_callkit_incoming

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat

class CallkitNotificationService : Service() {

    companion object {

        private val ActionForeground = listOf(
            CallkitConstants.ACTION_CALL_START,
            CallkitConstants.ACTION_CALL_ACCEPT,
            CallkitConstants.ACTION_CALL_CONNECTED
        )


        fun startServiceWithAction(context: Context, action: String, data: Bundle?) {
            val intent = Intent(context, CallkitNotificationService::class.java).apply {
                this.action = action
                putExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && intent.action in ActionForeground) {
                data?.let {
                    if(it.getBoolean(CallkitConstants.EXTRA_CALLKIT_CALLING_SHOW, true)) {
                        ContextCompat.startForegroundService(context, intent)
                    }else {
                        context.startService(intent)
                    }
                }
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, CallkitNotificationService::class.java)
            context.stopService(intent)
        }

    }

    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var ringbackTone: ToneGenerator? = null
    private var ringbackHandler: Handler? = null
    private var ringbackRunnable: Runnable? = null
    private var isRingbackPlaying = false
    private val RINGBACK_ON_MS = 2000L
    private val RINGBACK_OFF_MS = 2000L

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private fun getCallkitNotificationManager(): CallkitNotificationManager? {
        return CallkitNotificationManagerProvider.get(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action === CallkitConstants.ACTION_CALL_START) {
            intent.getBundleExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA)
                ?.let {
                    if(it.getBoolean(CallkitConstants.EXTRA_CALLKIT_CALLING_SHOW, true)) {
                        getCallkitNotificationManager()?.createNotificationChanel(it)
                        // Keep ringtone behavior while the outbound call is still ringing.
                        showOngoingCallNotification(
                            it,
                            ringingAudio = true
                        )
                    }else {
                        stopSelf()
                    }
                }
            startRingbackTone()
        }
        if (intent?.action === CallkitConstants.ACTION_CALL_ACCEPT) {
            intent.getBundleExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA)
                ?.let {
                    getCallkitNotificationManager()?.clearIncomingNotification(it, true)
                    if (it.getBoolean(CallkitConstants.EXTRA_CALLKIT_CALLING_SHOW, true)) {
                        showOngoingCallNotification(it)
                    }else {
                        stopSelf()
                    }
                }
            stopRingbackTone()
        }
        if (intent?.action === CallkitConstants.ACTION_CALL_CONNECTED) {
            // Now that the call is connected, ensure we are a proper foreground service.
            intent.getBundleExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA)
                ?.let {
                    getCallkitNotificationManager()?.createNotificationChanel(it)
                    showOngoingCallNotification(it)
                } ?: run {
                    // Fallback to audio focus even if data is missing.
                    ensureCallAudioKeepAlive()
                }
            stopRingbackTone()
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun showOngoingCallNotification(
        bundle: Bundle,
        ringingAudio: Boolean = false
    ) {
        if (ringingAudio) {
            ensureRingingAudioKeepAlive()
        } else {
            ensureCallAudioKeepAlive()
        }

        val callkitNotification =
            getCallkitNotificationManager()?.getOnGoingCallNotification(bundle, false)
        if (callkitNotification != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                }
                startForeground(
                    callkitNotification.id,
                    callkitNotification.notification,
                    foregroundServiceType
                )
            } else {
                startForeground(callkitNotification.id, callkitNotification.notification)
            }
        }
    }

    private fun ensureCallAudioKeepAlive() {
        acquireWakeLock()
        requestAudioFocus()
        setAudioMode()
    }

    private fun ensureRingingAudioKeepAlive() {
        acquireWakeLock()
        setRingingAudioMode()
    }

    private fun startRingbackTone() {
        if (isRingbackPlaying) return
        isRingbackPlaying = true
        if (ringbackTone == null) {
            ringbackTone = ToneGenerator(AudioManager.STREAM_RING, 80)
        }
        if (ringbackHandler == null) {
            ringbackHandler = Handler(Looper.getMainLooper())
        }
        if (ringbackRunnable == null) {
            ringbackRunnable = Runnable {
                if (!isRingbackPlaying) return@Runnable
                ringbackTone?.startTone(
                    ToneGenerator.TONE_SUP_RINGTONE,
                    RINGBACK_ON_MS.toInt()
                )
                ringbackHandler?.postDelayed(
                    ringbackRunnable!!,
                    RINGBACK_ON_MS + RINGBACK_OFF_MS
                )
            }
        }
        ringbackRunnable?.run()
    }

    private fun stopRingbackTone() {
        ringbackHandler?.removeCallbacks(ringbackRunnable ?: return)
        ringbackTone?.stopTone()
        ringbackTone?.release()
        ringbackTone = null
        ringbackRunnable = null
        isRingbackPlaying = false
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "CallkitNotificationService::WakeLock"
            )
            wakeLock?.acquire()
        } catch (_: Exception) {
            // Ignore
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        } catch (_: Exception) {
            // Ignore
        }
    }

    private fun requestAudioFocus(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener { }
                    .build()

                val result = audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
                    ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
                result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                val result = audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN
                )
                result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun releaseAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (_: Exception) {
            // Ignore
        } finally {
            audioFocusRequest = null
        }
    }

    private fun setAudioMode() {
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        } catch (_: Exception) {
            // Ignore
        }
    }

    private fun setRingingAudioMode() {
        try {
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {
            // Ignore
        }
    }

    private fun resetAudioMode() {
        try {
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {
            // Ignore
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
        stopRingbackTone()
        releaseAudioFocus()
        resetAudioMode()
        releaseWakeLock()
        // Don't destroy the notification manager here as it's shared across the app
        // The plugin will handle cleanup when all engines are detached
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }


    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }else {
            stopForeground(true)
        }
        releaseAudioFocus()
        resetAudioMode()
        releaseWakeLock()
        stopSelf()
    }



}
