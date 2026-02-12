package com.hiennv.flutter_callkit_incoming

import android.content.Context
import android.media.AudioManager
import android.os.Build

object CallkitAudioCleanup {
    fun resetIfNoActiveCalls(context: Context) {
        val calls = getDataActiveCalls(context)
        if (calls.isNotEmpty()) return

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
        } catch (_: Exception) {
        }

        try {
            if (audioManager.isBluetoothScoOn) {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            }
        } catch (_: Exception) {
        }

        try {
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {
        }

        try {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        } catch (_: Exception) {
        }
    }
}
