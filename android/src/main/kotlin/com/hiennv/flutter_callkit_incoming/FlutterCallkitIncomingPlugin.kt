package com.hiennv.flutter_callkit_incoming

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.NonNull
import com.hiennv.flutter_callkit_incoming.Utils.Companion.reapCollection
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.*
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.lang.ref.WeakReference


/** FlutterCallkitIncomingPlugin */
class FlutterCallkitIncomingPlugin : FlutterPlugin, MethodCallHandler, ActivityAware,
    PluginRegistry.RequestPermissionsResultListener {
    companion object {

        const val EXTRA_CALLKIT_CALL_DATA = "EXTRA_CALLKIT_CALL_DATA"

        @SuppressLint("StaticFieldLeak")
        private lateinit var instance: FlutterCallkitIncomingPlugin

        fun getInstance(): FlutterCallkitIncomingPlugin? {
            if (hasInstance()) {
                return instance
            }
            return null
        }

        fun hasInstance(): Boolean {
            return ::instance.isInitialized
        }

        private val methodChannels = mutableMapOf<BinaryMessenger, MethodChannel>()
        private val eventChannels = mutableMapOf<BinaryMessenger, EventChannel>()
        private val eventHandlers = mutableListOf<WeakReference<EventCallbackHandler>>()
        private val eventCallbacks = mutableListOf<WeakReference<CallkitEventCallback>>()

        fun sendEvent(event: String, body: Map<String, Any?>) {
            eventHandlers.reapCollection().forEach {
                it.get()?.send(event, body)
            }
        }

        public fun sendEventCustom(event: String, body: Map<String, Any>) {
            eventHandlers.reapCollection().forEach {
                it.get()?.send(event, body)
            }
        }

        /**
         * Register a callback to receive call events (accept/decline) natively.
         * This allows other plugins/services to handle call events
         * even when Flutter engine is terminated.
         */
        fun registerEventCallback(callback: CallkitEventCallback) {
            eventCallbacks.add(WeakReference(callback))
        }

        /**
         * Unregister an event callback.
         */
        fun unregisterEventCallback(callback: CallkitEventCallback) {
            eventCallbacks.removeAll { it.get() == callback || it.get() == null }
        }

        /**
         * Notify all registered event callbacks.
         * Called internally when a call event occurs.
         */
        internal fun notifyEventCallbacks(event: CallkitEventCallback.CallEvent, callData: android.os.Bundle) {
            eventCallbacks.reapCollection().forEach { callbackRef ->
                callbackRef.get()?.onCallEvent(event, callData)
            }
        }
        
        fun propagateHoldState(uuid: String, isOnHold: Boolean) {
            if (hasInstance()) {
                instance.syncHoldState(uuid, isOnHold, emitEvent = true)
            }
        }
        
        fun activateAndHoldOthers(uuid: String) {
            if (hasInstance()) {
                instance.setActiveAndHoldOthers(uuid)
            }
        }


        fun sharePluginWithRegister(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
            initSharedInstance(
                flutterPluginBinding.applicationContext,
                flutterPluginBinding.binaryMessenger
            )
        }

        fun initSharedInstance(context: Context, binaryMessenger: BinaryMessenger) {
            if (!::instance.isInitialized) {
                instance = FlutterCallkitIncomingPlugin()
                instance.callkitSoundPlayerManager = CallkitSoundPlayerManager(context)
                instance.callkitNotificationManager = CallkitNotificationManager(context, instance.callkitSoundPlayerManager)
                instance.context = context
            } else {
                // Re-initialize managers if they were destroyed but instance still exists
                if (instance.callkitNotificationManager == null) {
                    instance.callkitSoundPlayerManager = CallkitSoundPlayerManager(context)
                    instance.callkitNotificationManager = CallkitNotificationManager(context, instance.callkitSoundPlayerManager)
                }
            }

            val channel = MethodChannel(binaryMessenger, "flutter_callkit_incoming")
            methodChannels[binaryMessenger] = channel
            channel.setMethodCallHandler(instance)

            val events = EventChannel(binaryMessenger, "flutter_callkit_incoming_events")
            eventChannels[binaryMessenger] = events
            val handler = EventCallbackHandler()
            eventHandlers.add(WeakReference(handler))
            events.setStreamHandler(handler)

        }

    }

    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private var activity: Activity? = null
    private var context: Context? = null
    private var callkitNotificationManager: CallkitNotificationManager? = null
    private var callkitSoundPlayerManager: CallkitSoundPlayerManager? = null
    
    private fun syncHoldState(uuid: String, isOnHold: Boolean, emitEvent: Boolean = true): Boolean {
        val ctx = context ?: return false
        val (changed, found) = updateCallHoldState(ctx, uuid, isOnHold)
        if (emitEvent && (changed || !found)) {
            sendEvent(
                CallkitConstants.ACTION_CALL_TOGGLE_HOLD,
                mapOf("id" to uuid, "isOnHold" to isOnHold)
            )
            return true
        }
        return false
    }
    
    private fun setActiveAndHoldOthers(activeUuid: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        if (context == null) return false
        var emitted = false
        CallkitConnectionManager.getAllConnections().forEach { (uuid, connections) ->
            if (uuid == activeUuid) {
                connections.forEach { connection ->
                    if (connection.state != android.telecom.Connection.STATE_ACTIVE) {
                        connection.setActive()
                    }
                }
                emitted = syncHoldState(uuid, false, emitEvent = true) || emitted
            } else {
                connections.forEach { connection ->
                    if (connection.state != android.telecom.Connection.STATE_HOLDING) {
                        connection.setOnHold()
                    }
                }
                emitted = syncHoldState(uuid, true, emitEvent = true) || emitted
            }
        }
        return emitted
    }

    private fun findCallById(calls: List<Data>, id: String?): Data? {
        if (id.isNullOrEmpty()) return null
        val direct = calls.firstOrNull { it.id == id }
        if (direct != null) return direct
        return calls.firstOrNull { call ->
            val extraCallId = call.extra["callId"] as? String
            val embeddedId = call.extra["deviceEmbeddedCallId"] as? String
            extraCallId == id || embeddedId == id
        }
    }
    fun getCallkitNotificationManager(): CallkitNotificationManager? {
        return callkitNotificationManager
    }

    fun getCallkitSoundPlayerManager(): CallkitSoundPlayerManager? {
        return callkitSoundPlayerManager
    }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        sharePluginWithRegister(flutterPluginBinding)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            InAppCallManager(flutterPluginBinding.applicationContext).registerPhoneAccount()
        }
    }

    public fun showIncomingNotification(data: Data) {
        data.from = "notification"
        //send BroadcastReceiver
        context?.sendBroadcast(
            CallkitIncomingBroadcastReceiver.getIntentIncoming(
                requireNotNull(context),
                data.toBundle()
            )
        )
    }

    public fun showMissCallNotification(data: Data) {
        callkitNotificationManager?.showMissCallNotification(data.toBundle())
    }

    public fun startCall(data: Data) {
        context?.sendBroadcast(
            CallkitIncomingBroadcastReceiver.getIntentStart(
                requireNotNull(context),
                data.toBundle()
            )
        )
    }

    public fun endCall(data: Data) {
        context?.sendBroadcast(
            CallkitIncomingBroadcastReceiver.getIntentEnded(
                requireNotNull(context),
                data.toBundle()
            )
        )
    }

    public fun endAllCalls() {
        val calls = getDataActiveCalls(context)
        calls.forEach {
            context?.sendBroadcast(
                CallkitIncomingBroadcastReceiver.getIntentEnded(
                    requireNotNull(context),
                    it.toBundle()
                )
            )
        }
        removeAllCalls(context)
    }

    public fun sendEventCustom(body: Map<String, Any>) {
        eventHandlers.reapCollection().forEach {
            it.get()?.send(CallkitConstants.ACTION_CALL_CUSTOM, body)
        }
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        try {
            when (call.method) {
                "showCallkitIncoming" -> {
                    val data = Data(call.arguments() ?: HashMap())
                    data.from = "notification"
                    //send BroadcastReceiver
                    context?.sendBroadcast(
                        CallkitIncomingBroadcastReceiver.getIntentIncoming(
                            requireNotNull(context),
                            data.toBundle()
                        )
                    )

                    result.success(true)
                }

                "registerCallConnection" -> {
                    val args: Map<String, Any?>? = call.arguments()
                    val map: Map<String, Any?> = args ?: emptyMap<String, Any?>()
                    val dataMap: Map<String, Any?> =
                        (map["data"] as? Map<String, Any?>) ?: map
                    val isOutgoing = map["isOutgoing"] as? Boolean ?: false

                    val data = Data(dataMap)
                    if (context != null) {
                        addCall(context, data, isOutgoing)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            InAppCallManager.registerIncomingCall(
                                requireNotNull(context),
                                data.toBundle(),
                                isOutgoing
                            )
                        }
                    }

                    result.success(true)
                }

                "showCallkitIncomingSilently" -> {
                    val data = Data(call.arguments() ?: HashMap())
                    data.from = "notification"

                    result.success(true)
                }

                "startRingtone" -> {
                    val data = Data(call.arguments() ?: HashMap())
                    callkitSoundPlayerManager?.play(data.toBundle())
                    result.success(true)
                }

                "stopRingtone" -> {
                    callkitSoundPlayerManager?.stop()
                    result.success(true)
                }

                "showMissCallNotification" -> {
                    val data = Data(call.arguments() ?: HashMap())
                    data.from = "notification"
                    callkitNotificationManager?.showMissCallNotification(data.toBundle())
                    result.success(true)
                }

                "startCall" -> {
                    val data = Data(call.arguments() ?: HashMap())
                    context?.sendBroadcast(
                        CallkitIncomingBroadcastReceiver.getIntentStart(
                            requireNotNull(context),
                            data.toBundle()
                        )
                    )

                    result.success(true)
                }

                "muteCall" -> {
                    val map = buildMap {
                        val args = call.arguments
                        if (args is Map<*, *>) {
                            putAll(args as Map<String, Any>)
                        }
                    }
                    sendEvent(CallkitConstants.ACTION_CALL_TOGGLE_MUTE, map)

                    result.success(true)
                }

                "holdCall" -> {
                    val map = buildMap {
                        val args = call.arguments
                        if (args is Map<*, *>) {
                            putAll(args as Map<String, Any>)
                        }
                    }
                    var emittedByNative = false
                    val uuid = map["id"] as? String
                    val isOnHold = map["isOnHold"] as? Boolean ?: true
                    if (uuid != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if (isOnHold) {
                            CallkitConnectionManager.setConnectionOnHold(uuid)
                            syncHoldState(uuid, true, emitEvent = false)
                        } else {
                            emittedByNative = setActiveAndHoldOthers(uuid)
                        }
                    }

                    if (!emittedByNative) {
                        sendEvent(
                            CallkitConstants.ACTION_CALL_TOGGLE_HOLD,
                            map
                        )
                    }

                    result.success(true)
                }

                "isMuted" -> {
                    result.success(true)
                }

                "endCall" -> {
                    val calls = getDataActiveCalls(context)
                    val data = Data(call.arguments() ?: HashMap())
                    val currentCall = findCallById(calls, data.id)
                    if (currentCall != null && context != null) {
                        if(currentCall.isAccepted) {
                            context?.sendBroadcast(
                                CallkitIncomingBroadcastReceiver.getIntentEnded(
                                    requireNotNull(context),
                                    currentCall.toBundle()
                                )
                            )
                        }else {
                            context?.sendBroadcast(
                                CallkitIncomingBroadcastReceiver.getIntentDecline(
                                    requireNotNull(context),
                                    currentCall.toBundle()
                                )
                            )
                        }
                    } else if (context != null) {
                        // Fallback: ensure any ongoing call notification/service is cleared
                        CallkitNotificationManagerProvider.get(requireNotNull(context))
                            ?.clearIncomingNotification(data.toBundle(), false)
                        if (calls.isEmpty()) {
                            CallkitNotificationService.stopService(requireNotNull(context))
                        }
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                            val callUuid = data.id
                            if (callUuid.isNotEmpty()) {
                                CallkitConnectionManager.getConnections(callUuid).forEach { conn ->
                                    conn.setDisconnected(android.telecom.DisconnectCause(android.telecom.DisconnectCause.LOCAL))
                                    conn.destroy()
                                }
                                CallkitConnectionManager.removeConnection(callUuid)
                                CallkitTelecomRegistry.clear(callUuid)
                            }
                        }
                        CallkitAudioCleanup.resetIfNoActiveCalls(requireNotNull(context))
                    }
                    result.success(true)
                }

                "callConnected" -> {
                    val calls = getDataActiveCalls(context)
                    val data = Data(call.arguments() ?: HashMap())
                    val currentCall = findCallById(calls, data.id)
                    if (currentCall != null && context != null) {
                        context?.sendBroadcast(
                            CallkitIncomingBroadcastReceiver.getIntentConnected(
                                requireNotNull(context),
                                currentCall.toBundle()
                            )
                        )
                        
                        // Tell the Connection it's now active (API 23+)
                        // This triggers Android to automatically hold any active GSM calls
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                            setActiveAndHoldOthers(currentCall.id)
                        }
                    }
                    result.success(true)
                }

                "endAllCalls" -> {
                    val calls = getDataActiveCalls(context)
                    calls.forEach {
                        if (it.isAccepted) {
                            context?.sendBroadcast(
                                CallkitIncomingBroadcastReceiver.getIntentEnded(
                                    requireNotNull(context),
                                    it.toBundle()
                                )
                            )
                        } else {
                            context?.sendBroadcast(
                                CallkitIncomingBroadcastReceiver.getIntentDecline(
                                    requireNotNull(context),
                                    it.toBundle()
                                )
                            )
                        }
                    }
                    removeAllCalls(context)
                    if (context != null) {
                        CallkitAudioCleanup.resetIfNoActiveCalls(requireNotNull(context))
                    }
                    result.success(true)
                }

                "activeCalls" -> {
                    result.success(getDataActiveCallsForFlutter(context))
                }

                "getDevicePushTokenVoIP" -> {
                    result.success("")
                }

                "silenceEvents" -> {
                    val silence = call.arguments as? Boolean ?: false
                    CallkitIncomingBroadcastReceiver.silenceEvents = silence
                    result.success(true)
                }

                "requestNotificationPermission" -> {
                    val map = buildMap {
                        val args = call.arguments
                        if (args is Map<*, *>) {
                            putAll(args as Map<String, Any>)
                        }
                    }
                    callkitNotificationManager?.requestNotificationPermission(activity, map)
                    result.success(true)
                }

                "requestFullIntentPermission" -> {
                    callkitNotificationManager?.requestFullIntentPermission(activity)
                    result.success(true)
                }

                "canUseFullScreenIntent" -> {
                    result.success(callkitNotificationManager?.canUseFullScreenIntent() ?: true)
                }

                // EDIT - clear the incoming notification/ring (after accept/decline/timeout)
                "hideCallkitIncoming" -> {
                    val data = Data(call.arguments() ?: HashMap())
                    callkitSoundPlayerManager?.stop()
                    callkitNotificationManager?.clearIncomingNotification(data.toBundle(), false)
                    result.success(true)
                }

                "endNativeSubsystemOnly" -> {
                    result.success(true)
                }

                "setAudioRoute" -> {
                    val map = buildMap {
                        val args = call.arguments
                        if (args is Map<*, *>) {
                            putAll(args as Map<String, Any>)
                        }
                    }
                    
                    val uuid = map["uuid"] as? String
                    val route = map["route"] as? Int ?: 0 // 0=earpiece, 1=speaker, 2=bluetooth
                    emitAudioLog(
                        level = "info",
                        message = "setAudioRoute request uuid=${uuid ?: "null"} route=${flutterRouteName(route)}($route)",
                        extras = mapOf("uuid" to uuid, "route" to route)
                    )
                    
                    if (uuid != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        val connections = CallkitConnectionManager.getConnections(uuid)
                        if (connections.isNotEmpty()) {
                            // Map Flutter route to Android CallAudioState route
                            val audioRoute = when (route) {
                                1 -> android.telecom.CallAudioState.ROUTE_SPEAKER
                                2 -> android.telecom.CallAudioState.ROUTE_BLUETOOTH
                                3 -> android.telecom.CallAudioState.ROUTE_WIRED_HEADSET
                                else -> android.telecom.CallAudioState.ROUTE_EARPIECE
                            }

                            emitAudioLog(
                                level = "debug",
                                message = "sending request to system to change route to ${callAudioRouteName(audioRoute)} for call $uuid",
                                extras = mapOf(
                                    "uuid" to uuid,
                                    "route" to route,
                                    "connectionsCount" to connections.size
                                )
                            )
                            
                            connections.forEach { connection ->
                                applySupportedAudioRoutes(connection, route)
                                connection.setAudioRoute(audioRoute)
                            }
                            android.util.Log.d("CallkitIncoming", "Set audio route to $audioRoute for call $uuid")
                            // Best-effort enforcement: prevent Bluetooth from immediately re-asserting
                            val overrideApplied = applyAudioRouteOverride(route)
                            emitAudioLog(
                                level = "info",
                                message = "setAudioRoute completed uuid=$uuid route=${flutterRouteName(route)}($route) overrideApplied=$overrideApplied"
                            )
                            result.success(overrideApplied)
                        } else {
                            android.util.Log.w("CallkitIncoming", "Connection not found for uuid: $uuid")
                            emitAudioLog(
                                level = "warn",
                                message = "setAudioRoute skipped: no active connection for uuid=$uuid",
                                extras = mapOf("uuid" to uuid, "route" to route)
                            )
                            result.success(false)
                        }
                    } else {
                        emitAudioLog(
                            level = "warn",
                            message = "setAudioRoute rejected: invalid uuid or unsupported API (uuid=$uuid)",
                            extras = mapOf("uuid" to uuid, "route" to route)
                        )
                        result.success(false)
                    }
                }
            }
        } catch (error: Exception) {
            emitAudioLog(
                level = "error",
                message = "method ${call.method} failed: ${error.message ?: "unknown error"}"
            )
            result.error("error", error.message, "")
        }
    }

    private fun applySupportedAudioRoutes(connection: CallkitConnection, requestedRoute: Int) {
        try {
            val baseMask = connection.callAudioState?.supportedRouteMask
                ?: (android.telecom.CallAudioState.ROUTE_EARPIECE or
                    android.telecom.CallAudioState.ROUTE_SPEAKER or
                    android.telecom.CallAudioState.ROUTE_WIRED_HEADSET or
                    android.telecom.CallAudioState.ROUTE_BLUETOOTH)

            val newMask = if (requestedRoute == 2) {
                baseMask or android.telecom.CallAudioState.ROUTE_BLUETOOTH
            } else {
                baseMask and android.telecom.CallAudioState.ROUTE_BLUETOOTH.inv()
            }
            // setSupportedAudioRoutes is not available on all compile SDKs; call via reflection when present.
            try {
                val method = connection.javaClass.getMethod(
                    "setSupportedAudioRoutes",
                    Int::class.javaPrimitiveType
                )
                method.invoke(connection, newMask)
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
    }
    private fun applyAudioRouteOverride(route: Int): Boolean {
        val ctx = context ?: return false
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            try {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            } catch (_: Exception) {
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val availableDevices = describeCommunicationDevices(audioManager.availableCommunicationDevices)
                val beforeDevice = describeCommunicationDevice(audioManager.communicationDevice)
                if (route != 2) {
                    try {
                        audioManager.clearCommunicationDevice()
                    } catch (_: Exception) {
                    }
                }
                val devices = audioManager.availableCommunicationDevices
                val target = when (route) {
                    1 -> devices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    0 -> devices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    }
                    3 -> devices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    }
                    2 -> devices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                    }
                    else -> null
                }
                val targetDevice = describeCommunicationDevice(target)
                val applied = if (route == 2) {
                    target != null && audioManager.setCommunicationDevice(target)
                } else {
                    if (target != null) {
                        audioManager.setCommunicationDevice(target)
                    } else {
                        audioManager.clearCommunicationDevice()
                    }
                    true
                }
                @Suppress("DEPRECATION")
                if (route == 1) {
                    audioManager.isSpeakerphoneOn = true
                } else if (route == 0 || route == 3) {
                    audioManager.isSpeakerphoneOn = false
                }
                val afterDevice = describeCommunicationDevice(audioManager.communicationDevice)
                emitAudioLog(
                    level = "debug",
                    message = "applyAudioRouteOverride route=${flutterRouteName(route)} before=$beforeDevice after=$afterDevice target=$targetDevice visible=$availableDevices applied=$applied",
                    extras = mapOf("route" to route)
                )
                return applied
            } else {
                val applied = if (route == 2) {
                    val canUseSco =
                        audioManager.isBluetoothScoAvailableOffCall || isBluetoothHeadsetConnectedLegacy()
                    if (canUseSco) {
                        if (!audioManager.isBluetoothScoOn) {
                            try {
                                audioManager.startBluetoothSco()
                            } catch (_: Exception) {
                            }
                        }
                        audioManager.isBluetoothScoOn = true
                        true
                    } else {
                        false
                    }
                } else {
                    if (route == 1) {
                        audioManager.isSpeakerphoneOn = true
                    } else if (route == 0 || route == 3) {
                        audioManager.isSpeakerphoneOn = false
                    }
                    if (audioManager.isBluetoothScoOn) {
                        try {
                            audioManager.stopBluetoothSco()
                        } catch (_: Exception) {
                        }
                        audioManager.isBluetoothScoOn = false
                    }
                    true
                }
                emitAudioLog(
                    level = "debug",
                    message = "applyAudioRouteOverride legacy route=${flutterRouteName(route)} speaker=${audioManager.isSpeakerphoneOn} sco=${audioManager.isBluetoothScoOn} applied=$applied",
                    extras = mapOf("route" to route)
                )
                return applied
            }
        } catch (error: Exception) {
            emitAudioLog(
                level = "warn",
                message = "applyAudioRouteOverride failed route=${flutterRouteName(route)} error=${error.message ?: "unknown"}",
                extras = mapOf("route" to route)
            )
            return false
        }
    }

    private fun isBluetoothHeadsetConnectedLegacy(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return false
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
            adapter.getProfileConnectionState(BluetoothProfile.HEADSET) ==
                BluetoothProfile.STATE_CONNECTED
        } catch (_: Exception) {
            false
        }
    }

    private fun emitAudioLog(
        level: String,
        message: String,
        extras: Map<String, Any?> = emptyMap()
    ) {
        val body = mutableMapOf<String, Any?>(
            "source" to "f_c_i",
            "level" to level.lowercase(),
            "message" to message
        )
        body.putAll(extras)
        sendEvent(CallkitConstants.ACTION_CALL_CUSTOM, body)

        when (level.lowercase()) {
            "error" -> android.util.Log.e("CallkitIncoming", message)
            "warn" -> android.util.Log.w("CallkitIncoming", message)
            "debug" -> android.util.Log.d("CallkitIncoming", message)
            else -> android.util.Log.i("CallkitIncoming", message)
        }
    }

    private fun flutterRouteName(route: Int): String {
        return when (route) {
            1 -> "speaker"
            2 -> "bluetooth"
            3 -> "wired"
            else -> "earpiece"
        }
    }

    private fun callAudioRouteName(route: Int): String {
        return when (route) {
            android.telecom.CallAudioState.ROUTE_EARPIECE -> "earpiece"
            android.telecom.CallAudioState.ROUTE_BLUETOOTH -> "bluetooth"
            android.telecom.CallAudioState.ROUTE_WIRED_HEADSET -> "wired"
            android.telecom.CallAudioState.ROUTE_SPEAKER -> "speaker"
            else -> "unknown($route)"
        }
    }

    private fun describeCommunicationDevices(devices: List<AudioDeviceInfo>): String {
        if (devices.isEmpty()) return "none"
        return devices.joinToString(separator = ", ") { describeCommunicationDevice(it) }
    }

    private fun describeCommunicationDevice(device: AudioDeviceInfo?): String {
        if (device == null) return "none"
        val deviceName = device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "unnamed"
        return "${audioDeviceTypeName(device.type)}:$deviceName"
    }

    private fun audioDeviceTypeName(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_headphones"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth_sco"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "ble_headset"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "ble_speaker"
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> "ble_broadcast"
            else -> "type_$type"
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannels.remove(binding.binaryMessenger)?.setMethodCallHandler(null)
        eventChannels.remove(binding.binaryMessenger)?.setStreamHandler(null)

        // Only destroy managers when all engine bindings are detached
        // This prevents issues when foreground services detach but main app is still running
        if (methodChannels.isEmpty() && eventChannels.isEmpty()) {
            instance.callkitSoundPlayerManager?.destroy()
            instance.callkitNotificationManager?.destroy()
            instance.callkitSoundPlayerManager = null
            instance.callkitNotificationManager = null
        }
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        instance.context = binding.activity.applicationContext
        instance.activity = binding.activity
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        instance.context = binding.activity.applicationContext
        instance.activity = binding.activity
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivity() {
        instance.context = null
        instance.activity = null
    }

    class EventCallbackHandler : EventChannel.StreamHandler {

        private var eventSink: EventChannel.EventSink? = null

        override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
            eventSink = sink
        }

        fun send(event: String, body: Map<String, Any?>) {
            val data = mapOf(
                "event" to event,
                "body" to body
            )
            Handler(Looper.getMainLooper()).post {
                eventSink?.success(data)
            }
        }

        override fun onCancel(arguments: Any?) {
            eventSink = null
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {
        instance.callkitNotificationManager?.onRequestPermissionsResult(
            instance.activity,
            requestCode,
            grantResults
        )
        return true
    }


}
