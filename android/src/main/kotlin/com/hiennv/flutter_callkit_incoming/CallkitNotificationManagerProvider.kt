package com.hiennv.flutter_callkit_incoming

import android.content.Context

object CallkitNotificationManagerProvider {
    @Volatile
    private var fallbackManager: CallkitNotificationManager? = null

    @Volatile
    private var fallbackSoundPlayer: CallkitSoundPlayerManager? = null

    fun get(context: Context): CallkitNotificationManager? {
        FlutterCallkitIncomingPlugin.getInstance()
            ?.getCallkitNotificationManager()
            ?.let { return it }

        val appContext = context.applicationContext
        if (fallbackManager == null) {
            synchronized(this) {
                if (fallbackManager == null) {
                    val soundPlayer = CallkitSoundPlayerManager(appContext)
                    fallbackSoundPlayer = soundPlayer
                    fallbackManager = CallkitNotificationManager(appContext, soundPlayer)
                }
            }
        }
        return fallbackManager
    }

    fun destroyFallback() {
        fallbackManager?.destroy()
        fallbackManager = null
        fallbackSoundPlayer?.destroy()
        fallbackSoundPlayer = null
    }
}
