package com.hiennv.flutter_callkit_incoming

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import android.text.TextUtils

class CallkitSoundPlayerManager(private val context: Context) {

    private var vibrator: Vibrator? = null
    private var audioManager: AudioManager? = null

    private var ringtone: Ringtone? = null
    private var ringtonePlayer: MediaPlayer? = null

    private var isPlaying: Boolean = false
    private var previousAudioMode: Int? = null
    private var bluetoothScoStarted = false
    private var bluetoothRoutingEnabled = false
    private var previousCommunicationDevice: AudioDeviceInfo? = null


    inner class ScreenOffCallkitIncomingBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (isPlaying){
                stop()
            }
        }
    }

    private var screenOffCallkitIncomingBroadcastReceiver = ScreenOffCallkitIncomingBroadcastReceiver()


    fun play(data: Bundle) {
        val sound = data.getString(CallkitConstants.EXTRA_CALLKIT_RINGTONE_PATH, "")
        if (sound == "silent") return

        this.isPlaying = true
        this.prepare()
        this.playSound(data)
        this.playVibrator()

        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        context.registerReceiver(screenOffCallkitIncomingBroadcastReceiver, filter)
    }

    fun stop() {
        this.isPlaying = false

        stopPlayback()
        vibrator?.cancel()
        vibrator = null
        try {
            context.unregisterReceiver(screenOffCallkitIncomingBroadcastReceiver)
        }catch (_: Exception){}
    }

    fun destroy() {
        this.isPlaying = false

        stopPlayback()
        vibrator?.cancel()
        vibrator = null
        try {
            context.unregisterReceiver(screenOffCallkitIncomingBroadcastReceiver)
        }catch (_: Exception){}
    }

    private fun prepare() {
        stopPlayback()
        vibrator?.cancel()
    }

    private fun playVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager =
                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (audioManager?.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> {
            }

            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(
                        VibrationEffect.createWaveform(
                            longArrayOf(0L, 1000L, 1000L),
                            0
                        )
                    )
                } else {
                    vibrator?.vibrate(longArrayOf(0L, 1000L, 1000L), 0)
                }
            }
        }
    }

    private fun playSound(data: Bundle?) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        this.audioManager = audioManager

        val bluetoothDevice = getBluetoothOutputDevice(audioManager)
        val bluetoothConnected =
            bluetoothDevice != null || isBluetoothConnectedLegacy(audioManager)
        if (audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL && !bluetoothConnected) {
            return
        }

        val sound = data?.getString(
            CallkitConstants.EXTRA_CALLKIT_RINGTONE_PATH,
            ""
        )
        val uri = sound?.let { getRingtoneUri(it) }
        if (uri == null) return

        val useVoiceUsage = bluetoothConnected
        val attributes = AudioAttributes.Builder()
            .setUsage(
                if (useVoiceUsage) AudioAttributes.USAGE_VOICE_COMMUNICATION
                else AudioAttributes.USAGE_NOTIFICATION_RINGTONE
            )
            .setContentType(
                if (useVoiceUsage) AudioAttributes.CONTENT_TYPE_SPEECH
                else AudioAttributes.CONTENT_TYPE_SONIFICATION
            )
            .build()

        try {
            if (bluetoothConnected) {
                enableBluetoothRouting(audioManager, bluetoothDevice)
            }

            if (bluetoothConnected || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                val player = MediaPlayer()
                player.setAudioAttributes(attributes)
                player.setDataSource(context, uri)
                player.isLooping = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && bluetoothDevice != null) {
                    player.setPreferredDevice(bluetoothDevice)
                }
                player.prepare()
                player.start()
                ringtonePlayer = player
            } else {
                ringtone = RingtoneManager.getRingtone(context, uri)
                ringtone?.setAudioAttributes(attributes)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ringtone?.isLooping = true
                }
                ringtone?.play()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            stopPlayback()
        }
    }

    private fun getRingtoneUri(fileName: String): Uri? {
        if (TextUtils.isEmpty(fileName)) {
            return getDefaultRingtoneUri()
        }
        
        // If system_ringtone_default is explicitly requested, bypass resource check
        if (fileName.equals("system_ringtone_default", true)) {
            return getDefaultRingtoneUri(useSystemDefault = true)
        }

        try {
            val resId = context.resources.getIdentifier(fileName, "raw", context.packageName)
            if (resId != 0) {
                return Uri.parse("android.resource://${context.packageName}/$resId")
            }

            // For any other unresolved filename, return the default ringtone
            return getDefaultRingtoneUri()
        } catch (e: Exception) {
            // If anything fails, try to return the system default ringtone
            return getDefaultRingtoneUri()
        }
    }

    private fun getDefaultRingtoneUri(useSystemDefault: Boolean = false): Uri? {
        try {
            if (!useSystemDefault) {
                // First try to use ringtone_default resource if it exists
                val resId = context.resources.getIdentifier("ringtone_default", "raw", context.packageName)
                if (resId != 0) {
                    return Uri.parse("android.resource://${context.packageName}/$resId")
                }
            }

            // Fall back to system default ringtone
            return RingtoneManager.getActualDefaultRingtoneUri(
                context,
                RingtoneManager.TYPE_RINGTONE
            )
        } catch (e: Exception) {
            // getActualDefaultRingtoneUri can throw an exception on some devices
            // for custom ringtones
            return getSafeSystemRingtoneUri()
        }
    }

    private fun getSafeSystemRingtoneUri(): Uri? {
        try {
            val defaultUri = RingtoneManager.getActualDefaultRingtoneUri(
                context,
                RingtoneManager.TYPE_RINGTONE
            )

            val rm = RingtoneManager(context)
            rm.setType(RingtoneManager.TYPE_RINGTONE)
            val cursor = rm.cursor
            if (defaultUri != null && cursor != null) {
                while (cursor.moveToNext()) {
                    val uri = rm.getRingtoneUri(cursor.position)
                    if (uri == defaultUri) {
                        cursor.close()
                        return defaultUri
                    }
                }
            }

            // Default isn't system-provided → fallback to first available
            if (cursor != null && cursor.moveToFirst()) {
                val fallback = rm.getRingtoneUri(cursor.position)
                cursor.close()
                return fallback
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun stopPlayback() {
        try {
            ringtone?.stop()
        } catch (_: Exception) {
        }
        ringtone = null

        ringtonePlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    player.stop()
                }
            } catch (_: Exception) {
            }
            player.release()
        }
        ringtonePlayer = null

        audioManager?.let { manager ->
            disableBluetoothRouting(manager)
        }
    }

    private fun isBluetoothConnectedLegacy(audioManager: AudioManager): Boolean {
        return audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn
    }

    private fun getBluetoothOutputDevice(audioManager: AudioManager): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.firstOrNull { isBluetoothOutput(it) }
    }

    private fun isBluetoothOutput(device: AudioDeviceInfo): Boolean {
        return when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> true
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
            } else {
                false
            }
        }
    }

    private fun enableBluetoothRouting(manager: AudioManager, bluetoothDevice: AudioDeviceInfo?) {
        if (bluetoothRoutingEnabled) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val btDevice = manager.availableCommunicationDevices.firstOrNull { device ->
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            if (btDevice != null) {
                previousCommunicationDevice = manager.communicationDevice
                manager.setCommunicationDevice(btDevice)
                bluetoothRoutingEnabled = true
                return
            }
        }

        val skipSco = bluetoothDevice != null && when (bluetoothDevice.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> true
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                bluetoothDevice.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
            } else {
                false
            }
        }
        if (skipSco) return
        if (!manager.isBluetoothScoAvailableOffCall) return

        bluetoothRoutingEnabled = true
        previousAudioMode = manager.mode
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (!manager.isBluetoothScoOn) {
            bluetoothScoStarted = true
            manager.startBluetoothSco()
            manager.isBluetoothScoOn = true
        }
    }

    private fun disableBluetoothRouting(manager: AudioManager) {
        if (!bluetoothRoutingEnabled) return
        bluetoothRoutingEnabled = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            previousCommunicationDevice?.let { manager.setCommunicationDevice(it) }
            previousCommunicationDevice = null
        }

        if (bluetoothScoStarted) {
            try {
                manager.stopBluetoothSco()
            } catch (_: Exception) {
            }
            manager.isBluetoothScoOn = false
            bluetoothScoStarted = false
        }

        previousAudioMode?.let { manager.mode = it }
        previousAudioMode = null
    }
}
